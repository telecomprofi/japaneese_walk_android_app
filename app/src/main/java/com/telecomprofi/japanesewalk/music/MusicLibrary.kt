package com.telecomprofi.japanesewalk.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.telecomprofi.japanesewalk.data.db.AppDatabase
import com.telecomprofi.japanesewalk.data.db.CatalogueEntity
import com.telecomprofi.japanesewalk.data.db.BpmSource
import com.telecomprofi.japanesewalk.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "MusicLibrary"


/** How deep to walk the granted folder. ARTIST/ALBUM trees sit a few levels down. */
private const val MAX_DEPTH = 5

/**
 * Written when a file cannot be decoded, so it reads as "measured" and is not
 * retried on every visit to the Music tab. Well below anything real, so it is
 * ignored when the playlist's levelling target is chosen.
 */
private const val FAILED_LOUDNESS = -99.0

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
     * Whether the app can still open the queued tracks, in words.
     *
     * SAF grants are persisted, but they do not always survive: a reboot, an
     * update, or the user clearing the app's data can take them away, and the
     * only symptom is that music silently stops playing. Asking directly turns
     * that into a sentence with an action in it.
     */
    fun accessProblem(tracks: List<TrackEntity>): String? {
        if (tracks.isEmpty()) return null
        val persisted = try {
            context.contentResolver.persistedUriPermissions.map { it.uri.toString() }
        } catch (e: Exception) {
            return null
        }
        val reachable = tracks.count { track -> persisted.any { track.uri.startsWith(it) } }
        return when {
            persisted.isEmpty() || reachable == 0 ->
                "Android has forgotten the permission on your music folder, so nothing " +
                    "can be played. Pick the folder again below — your playlist is kept."
            reachable < tracks.size ->
                "${tracks.size - reachable} of ${tracks.size} queued tracks are out of reach. " +
                    "Pick the folder again to restore them."
            else -> null
        }
    }

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
            val pending = trackDao.pendingAnalysis()
            var done = 0
            for (track in pending) {
                currentCoroutineContext().ensureActive()
                onProgress(AnalysisProgress(done, pending.size, track.displayName))
                val result = analyse(Uri.parse(track.uri))
                // Stamp analyzedAt either way, so a file that cannot be decoded
                // is not re-attempted on every scan. A failed decode also has to
                // leave a loudness that reads as measured, or pendingAnalysis()
                // would hand it back forever.
                if (result != null) {
                    trackDao.setAnalysis(
                        uri = track.uri,
                        bpm = result.bpm,
                        conf = result.confidence,
                        lufs = result.lufs ?: FAILED_LOUDNESS,
                        at = System.currentTimeMillis(),
                        source = result.source,
                    )
                    if (result.bpm <= 0) Log.w(TAG, "tempo analysis failed for ${track.fileName}")
                } else {
                    trackDao.setAnalysis(track.uri, 0.0, 0.0, FAILED_LOUDNESS, System.currentTimeMillis())
                    Log.w(TAG, "decode failed for ${track.fileName}")
                }
                done++
                onProgress(AnalysisProgress(done, pending.size, track.displayName))
            }
            done
        }

    suspend fun reanalyseAll(onProgress: (AnalysisProgress) -> Unit = {}): Int =
        withContext(Dispatchers.Default) {
            trackDao.clearAnalysis()
            analysePending(onProgress)
        }

    /**
     * Remove tracks the player can never read from the queue.
     *
     * They were scanned in before the app knew better, and they fail one at a
     * time, mid-walk, with a banner each. Dropping them in one go is quieter.
     */
    suspend fun dropUnplayable(): Int = withContext(Dispatchers.IO) {
        val queued = trackDao.allOnce()
        var dropped = 0
        for (track in queued) {
            if (!PlayableFormats.isPlayable(track.fileName)) {
                trackDao.setMissing(track.uri, true)
                dropped++
            }
        }
        if (dropped > 0) Log.i(TAG, "dropped $dropped track(s) in formats the player cannot read")
        dropped
    }

    /**
     * Take the tempo from the file's own tag wherever measurement was used and a
     * tag exists. Reading a few hundred kilobytes per track is fast enough to
     * sweep a large library in a minute, and it needs no audio decoding.
     */
    suspend fun adoptTags(onProgress: (AnalysisProgress) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val measured = trackDao.allOnce().filter { it.bpmSource == BpmSource.MEASURED }
            var upgraded = 0
            measured.forEachIndexed { index, track ->
                currentCoroutineContext().ensureActive()
                if (index % 25 == 0) {
                    onProgress(AnalysisProgress(index, measured.size, "Reading tags…"))
                }
                val tagged = readBpmTag(Uri.parse(track.uri)) ?: return@forEachIndexed
                if (tagged != track.bpm) {
                    trackDao.setAnalysis(
                        uri = track.uri,
                        bpm = tagged,
                        conf = 1.0,
                        lufs = track.loudnessLufs,
                        at = if (track.analyzedAt > 0) track.analyzedAt else System.currentTimeMillis(),
                        source = BpmSource.TAG,
                    )
                    upgraded++
                }
            }
            upgraded
        }

    /** Tempo and loudness from a single decode; the decode is the expensive part. */
    private data class Measurement(
        val bpm: Double,
        val confidence: Double,
        val lufs: Double?,
        val source: Int = BpmSource.MEASURED,
    )

    private fun analyse(uri: Uri): Measurement? {
        // The file's own tag first. Measuring tempo from audio is hard and goes
        // wrong in a particular way on dance music — two-thirds or half of the
        // real figure — while a tag written by iTunes, Traktor or a Beatport
        // download is simply correct. Loudness still needs the decode.
        val tagged = readBpmTag(uri)
        val decoded = AudioDecoder.decodeMonoExcerpt(context, uri)
            ?: return tagged?.let { Measurement(it, 1.0, null, BpmSource.TAG) }
        if (tagged != null) {
            return Measurement(tagged, 1.0, decoded.lufs, BpmSource.TAG)
        }
        val tempo = TempoEstimator.estimate(decoded.mono)
        return Measurement(tempo.bpm, tempo.confidence, decoded.lufs)
    }

    /** The first few hundred kilobytes are where a BPM tag lives, if anywhere. */
    private fun readBpmTag(uri: Uri): Double? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val head = ByteArray(BpmTags.HEAD_BYTES)
            var read = 0
            while (read < head.size) {
                val n = stream.read(head, read, head.size - read)
                if (n <= 0) break
                read += n
            }
            if (read <= 0) null else BpmTags.fromHeader(head.copyOf(read))
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read tags from $uri", e)
        null
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

    /**
     * Formats the *player* can read, which is not the same as the formats the
     * phone can decode.
     *
     * Windows Media is the one that catches people out. Some phones — Samsung's
     * especially — ship a platform WMA decoder, so the analyser happily reads
     * the tempo and loudness and the track looks perfectly healthy in the
     * playlist. Media3 has no WMA extractor at all, so the moment it is asked
     * to play, it refuses. A library synced from Windows Media Player can be
     * half WMA, which is exactly how every other song came to be skipped.
     */
    private fun isAudio(name: String): Boolean = PlayableFormats.isPlayable(name)

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
