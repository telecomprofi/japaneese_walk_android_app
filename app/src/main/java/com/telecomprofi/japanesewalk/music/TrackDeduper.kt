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

    /**
     * Words that mark one release of a song rather than a different song.
     * "Uptown Girl", "Uptown Girl (Extended Mix)" and "Uptown Girl - sped up"
     * are the same three minutes of music as far as a walk is concerned, and
     * hearing them ten seconds apart is the thing this exists to prevent.
     */
    private val VERSION_WORDS = listOf(
        "extended", "radio edit", "radio mix", "club mix", "club edit", "original mix",
        "remix", "rmx", "remaster", "remastered", "remasterd", "re-master", "version",
        "edit", "mix", "live", "acoustic", "instrumental", "karaoke", "cover",
        "sped up", "speed up", "speeded up", "slowed", "reverb", "nightcore",
        "bootleg", "mashup", "rework", "vip", "dub", "single", "album version",
        "mono", "stereo", "deluxe", "bonus", "demo", "reprise", "edit version",
    )

    /**
     * A key that is the same for every version of the same song.
     *
     * Deliberately keyed on the title alone. Sped-up and remixed uploads are
     * routinely credited to somebody else, so folding the artist in would let
     * exactly the pairs this is meant to separate slip through; the cost is
     * that two different songs sharing a title never play close together, which
     * nobody has ever complained about.
     */
    fun songKey(title: String?, fileName: String): String {
        var t = (title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'))
            .lowercase()
            .trim()

        // "01 - ", "07. ", "1_" — file naming, not part of the song.
        t = t.replace(Regex("""^\s*\d{1,3}\s*[-._)]\s*"""), "")
        // Anything credited after a "feat" is the same song with a guest on it.
        t = t.replace(Regex("""\b(feat|ft|featuring|with)\b.*$"""), " ")

        // Bracketed and dash-suffixed qualifiers, but only when they actually
        // name a version: "(Live at Wembley)" goes, "(Reprise)" goes, and a
        // title that is genuinely bracketed — "Song (Part Two)" — stays.
        val versioned = Regex("""[\[(][^\[\]()]*[\])]""")
        t = versioned.replace(t) { m ->
            if (marksAVersion(m.value)) " " else m.value
        }
        val dashSuffix = Regex("""\s[-–—]\s[^-–—]*$""")
        val tail = dashSuffix.find(t)?.value
        if (tail != null && marksAVersion(tail)) {
            t = dashSuffix.replace(t, " ")
        }

        // Everything that is not a letter or a digit is noise for this purpose.
        t = t.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        return t.ifEmpty { fileName.lowercase() }
    }

    /**
     * Whole words only: a bracketed "(Delivery)" contains the letters of "live"
     * and is not a live recording.
     */
    private fun marksAVersion(text: String): Boolean =
        VERSION_WORDS.any { w -> Regex("\\b" + Regex.escape(w) + "\\b").containsMatchIn(text) }

    /** Two entries that are versions of one song. */
    fun sameSong(titleA: String?, fileA: String, titleB: String?, fileB: String): Boolean =
        songKey(titleA, fileA) == songKey(titleB, fileB)
}
