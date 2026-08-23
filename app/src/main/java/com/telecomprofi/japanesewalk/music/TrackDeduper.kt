package com.telecomprofi.japanesewalk.music

/** One audio file being considered for the library. */
data class DedupeCandidate(
    val uri: String,
    val fileName: String,
    /** Path relative to the scanned folder; the shortest one wins a tie. */
    val relPath: String,
    val durationMs: Long = 0L,
)

/**
 * Music apps keep more than one copy of the same song. doubleTwist, for one,
 * syncs into `Music/ARTIST/ALBUM/` and can also leave copies under
 * `Android/data/<package>/files/Music/` — so a deep folder scan finds the same
 * track two or three times, each with its own document URI. Primary keys cannot
 * catch that; only content identity can.
 *
 * Identity here is file name plus duration rounded to the second, which is
 * cheap, needs no hashing, and does not treat two genuinely different songs as
 * one. Of the copies, the one with the shortest relative path is kept, which
 * reliably prefers `Music/x.mp3` over `Android/data/.../Music/x.mp3`.
 */
object TrackDeduper {

    fun key(fileName: String, durationMs: Long): String {
        val name = fileName.lowercase().trim()
        // Duration is unknown often enough that it must not split a group.
        return if (durationMs > 0) "$name#${durationMs / 1000}" else name
    }

    fun key(candidate: DedupeCandidate): String = key(candidate.fileName, candidate.durationMs)

    /** Collapse duplicates, keeping the copy in the shallowest location. */
    fun distinct(candidates: List<DedupeCandidate>): List<DedupeCandidate> {
        val best = LinkedHashMap<String, DedupeCandidate>()
        for (candidate in candidates) {
            val k = key(candidate)
            val existing = best[k]
            if (existing == null || preferred(candidate, existing)) best[k] = candidate
        }
        return best.values.toList()
    }

    /** How many were dropped, for reporting back to the user. */
    fun duplicateCount(candidates: List<DedupeCandidate>): Int =
        candidates.size - distinct(candidates).size

    private fun preferred(candidate: DedupeCandidate, incumbent: DedupeCandidate): Boolean {
        val depth = candidate.relPath.count { it == '/' }
        val incumbentDepth = incumbent.relPath.count { it == '/' }
        if (depth != incumbentDepth) return depth < incumbentDepth
        if (candidate.relPath.length != incumbent.relPath.length) {
            return candidate.relPath.length < incumbent.relPath.length
        }
        return false
    }

    /**
     * Names that mark a private per-app copy rather than the user's own library.
     * Scanning skips these outright, which removes most duplicates before they
     * are even considered.
     */
    val SKIPPED_DIRECTORIES: Set<String> = setOf("android", ".thumbnails", "lost.dir", ".trashed")

    fun isSkippedDirectory(name: String): Boolean = name.lowercase() in SKIPPED_DIRECTORIES
}
