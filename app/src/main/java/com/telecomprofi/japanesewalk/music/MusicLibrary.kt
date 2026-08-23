package com.telecomprofi.japanesewalk.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.telecomprofi.japanesewalk.data.db.AppDatabase
import com.telecomprofi.japanesewalk.data.db.CatalogueEntity
import com.telecomprofi.japanesewalk.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "MusicLibrary"

/** How deep to walk the granted folder. ARTIST/ALBUM trees sit a few levels down. */
private const val MAX_DEPTH = 5

data class CatalogueReport(
    val filesFound: Int = 0,
    val duplicatesSkipped: Int = 0,
    val catalogued: Int = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && catalogued > 0
}

data class AnalysisProgress(val done: Int, val total: Int, val currentTitle: String?)

private data class FoundFile(val doc: DocumentFile, val relPath: String) {
    val name: String get() = doc.name ?: relPath.substringAfterLast('/')
}

/**
 * Two collections, deliberately separate:
 *
 *  - the **catalogue** is everything found in the folder you granted, with its
 *    ID3 tags, so tracks can be found by searching rather than by remembering
 *    where they live;
 *  - the **workout list** ([TrackEntity]) is the handful you actually picked,
 *    and only those get their tempo measured.
 *
 * Access goes through the Storage Access Framework rather than raw file paths,
 * which is what allows a real SD card to be read on Android 13+ without the
 * all-files permission, and the grant survives reboots.
 */
class MusicLibrary(
    private val context: Context,
    private val db: AppDatabase,
) {
    private val trackDao = db.trackDao()
    private val catalogueDao = db.catalogueDao()

    /**
     * Walk the granted folder and record what is there, tags and all.
     *
     * @param onProgress phase updates; a full SD card takes real time and must
     *        never look like a hang.
     */
    suspend fun scanFolder(
        treeUri: String?,
        onProgress: (String) -> Unit = {},
    ): CatalogueReport = withContext(Dispatchers.IO) {
        if (treeUri.isNullOrBlank()) {
            return@withContext CatalogueReport(error = "No music folder selected yet")
        }
        val root = try {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        } catch (e: Exception) {
            null
        } ?: return@withContext CatalogueReport(error = "That folder is no longer reachable — pick it again")

        if (!root.isDirectory) return@withContext CatalogueReport(error = "Selected item is not a folder")

        onProgress("Scanning folder…")
        val found = mutableListOf<FoundFile>()
        index(root, "", 0, found, onProgress)

        if (found.isEmpty()) {
            return@withContext CatalogueReport(
                error = "No audio files found. Pick the folder that actually holds the music — " +
                    "on an SD card that is usually the card root or its Music folder.",
            )
        }

        // Tags must be read before de-duplicating: identity is name PLUS duration,
        // and collapsing on the name alone would throw away genuinely different
        // songs that happen to share one ("01 Intro.mp3" is on half the albums
        // ever ripped).
        val shortlist = found
        onProgress("Reading tags for ${shortlist.size} tracks…")
        val now = System.currentTimeMillis()
        val rows = mutableListOf<CatalogueEntity>()
        val withDuration = mutableListOf<DedupeCandidate>()

        shortlist.forEachIndexed { index, file ->
            currentCoroutineContext().ensureActive()
            if (index % 20 == 0) onProgress("Reading tags ${index + 1} of ${shortlist.size}…")
            val meta = readMetadata(file.doc.uri)
            val uri = file.doc.uri.toString()
            withDuration += DedupeCandidate(uri, file.name, file.relPath, meta?.durationMs ?: 0L)
            rows += CatalogueEntity(
                uri = uri,
                fileName = file.name,
                relPath = file.relPath,
                title = meta?.title,
                artist = meta?.artist,
                album = meta?.album,
                durationMs = meta?.durationMs ?: 0L,
                scannedAt = now,
            )
        }

        // Second pass now that durations are known: same name, same length.
        val keep = TrackDeduper.distinct(withDuration).map { it.uri }.toSet()
        val deduped = rows.filter { it.uri in keep }

        db.withTransaction {
            catalogueDao.clear()
            catalogueDao.upsert(deduped)
        }

        CatalogueReport(
            filesFound = found.size,
            duplicatesSkipped = found.size - deduped.size,
            catalogued = deduped.size,
        )
    }

    /** Add catalogue entries to the workout list, appended in the order given. */
    suspend fun addToWorkout(uris: List<String>): Int = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext 0
        // byUris has no ORDER BY, so restore the caller's ordering explicitly.
        val rank = uris.withIndex().associate { (index, uri) -> uri to index }
        val items = catalogueDao.byUris(uris).sortedBy { rank[it.uri] ?: Int.MAX_VALUE }
        if (items.isEmpty()) return@withContext 0
        val existing = trackDao.allOnce()
        val existingKeys = existing.map { TrackDeduper.key(it.fileName, it.durationMs) }.toMutableSet()
        var order = trackDao.maxOrder()
        val toAdd = mutableListOf<TrackEntity>()
        for (item in items) {
            val key = TrackDeduper.key(item.fileName, item.durationMs)
            // Never queue the same song twice, whichever copy it came from.
            if (!existingKeys.add(key)) continue
            toAdd += TrackEntity(
                uri = item.uri,
                fileName = item.fileName,
                title = item.title,
                artist = item.artist,
                durationMs = item.durationMs,
                bpm = 0.0,
                bpmConfidence = 0.0,
                analyzedAt = 0L,
                playlistOrder = ++order,
                missing = false,
            )
        }
        trackDao.upsert(toAdd)
        toAdd.size
    }

    /**
     * Add files chosen straight from the system picker, which may live outside
     * the granted folder entirely.
     */
    suspend fun addFilesToWorkout(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext 0
        val existing = trackDao.allOnce()
        val existingKeys = existing.map { TrackDeduper.key(it.fileName, it.durationMs) }.toMutableSet()
        var order = trackDao.maxOrder()
        val now = System.currentTimeMillis()
        val toAdd = mutableListOf<TrackEntity>()
        val catalogueRows = mutableListOf<CatalogueEntity>()

        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val name = displayName(uri) ?: continue
            val meta = readMetadata(uri)
            val key = TrackDeduper.key(name, meta?.durationMs ?: 0L)
            if (!existingKeys.add(key)) continue
            toAdd += TrackEntity(
                uri = uri.toString(),
                fileName = name,
                title = meta?.title,
                artist = meta?.artist,
                durationMs = meta?.durationMs ?: 0L,
                bpm = 0.0,
                bpmConfidence = 0.0,
                analyzedAt = 0L,
                playlistOrder = ++order,
                missing = false,
            )
            catalogueRows += CatalogueEntity(
                uri = uri.toString(),
                fileName = name,
                relPath = name,
                title = meta?.title,
                artist = meta?.artist,
                album = meta?.album,
                durationMs = meta?.durationMs ?: 0L,
                scannedAt = now,
            )
        }
        trackDao.upsert(toAdd)
        catalogueDao.upsert(catalogueRows)
        toAdd.size
    }

    suspend fun removeFromWorkout(uri: String) = withContext(Dispatchers.IO) {
        trackDao.delete(uri)
    }

    suspend fun clearWorkout() = withContext(Dispatchers.IO) {
        trackDao.clear()
    }

    /**
     * Measure the tempo of every queued track that does not have one yet.
     * Runs on the default dispatcher; call it from a coroutine you can cancel.
     */
    suspend fun analysePending(onProgress: (AnalysisProgress) -> Unit = {}): Int =
        withContext(Dispatchers.Default) {
            val pending = trackDao.unanalysed()
            var done = 0
            for (track in pending) {
                currentCoroutineContext().ensureActive()
                onProgress(AnalysisProgress(done, pending.size, track.displayName))
                val result = analyse(Uri.parse(track.uri))
                // Stamp analyzedAt either way, so a file that cannot be decoded
                // is not re-attempted on every scan.
                if (result != null && result.bpm > 0) {
                    trackDao.setBpm(track.uri, result.bpm, result.confidence, System.currentTimeMillis())
                } else {
                    trackDao.setBpm(track.uri, 0.0, 0.0, System.currentTimeMillis())
                    Log.w(TAG, "tempo analysis failed for ${track.fileName}")
                }
                done++
                onProgress(AnalysisProgress(done, pending.size, track.displayName))
            }
            done
        }

    suspend fun reanalyseAll(onProgress: (AnalysisProgress) -> Unit = {}): Int =
        withContext(Dispatchers.Default) {
            trackDao.allOnce().forEach { trackDao.setBpm(it.uri, 0.0, 0.0, 0L) }
            analysePending(onProgress)
        }

    private fun analyse(uri: Uri): TempoResult? {
        val decoded = AudioDecoder.decodeMonoExcerpt(context, uri) ?: return null
        return TempoEstimator.estimate(decoded.mono)
    }

    private suspend fun index(
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        audio: MutableList<FoundFile>,
        onProgress: (String) -> Unit,
    ) {
        if (depth >= MAX_DEPTH) return
        currentCoroutineContext().ensureActive()
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            Log.w(TAG, "cannot list ${dir.uri}", e)
            return
        }
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                // Per-app private copies are where the duplicates come from.
                if (TrackDeduper.isSkippedDirectory(name)) continue
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                onProgress("Scanning ${rel.take(48)}…")
                index(child, rel, depth + 1, audio, onProgress)
            } else if (isAudio(name)) {
                audio += FoundFile(child, if (prefix.isEmpty()) name else "$prefix/$name")
                if (audio.size % 50 == 0) onProgress("Found ${audio.size} audio files…")
            }
        }
    }

    private fun isAudio(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac") ||
            lower.endsWith(".flac") || lower.endsWith(".ogg") || lower.endsWith(".opus") ||
            lower.endsWith(".wav") || lower.endsWith(".wma")
    }

    private fun displayName(uri: Uri): String? =
        try {
            DocumentFile.fromSingleUri(context, uri)?.name ?: Uri.decode(uri.toString()).substringAfterLast('/')
        } catch (e: Exception) {
            null
        }

    private data class Meta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
    )

    private fun readMetadata(uri: Uri): Meta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Meta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            )
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
