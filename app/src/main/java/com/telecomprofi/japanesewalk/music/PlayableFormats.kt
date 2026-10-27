package com.telecomprofi.japanesewalk.music

/**
 * What the player can actually read — which is not the same as what the phone
 * can decode.
 *
 * Windows Media is the trap. Several phones, Samsung's in particular, ship a
 * platform WMA decoder, so the analyser reads the tempo and the loudness
 * happily and the track sits in the playlist looking perfectly healthy. Media3
 * has no WMA extractor at all, so the moment that track is asked to play it
 * refuses. A library synced from Windows Media Player can be half WMA, and
 * that is how every other song came to be skipped mid-walk.
 *
 * Kept as a list of extensions rather than sniffed from the file because the
 * question is asked thousands of times during a folder scan, and because the
 * answer has to be the same in the scanner, the cleanup and the player.
 */
object PlayableFormats {

    val EXTENSIONS: List<String> = listOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "mkv", "mp4",
    )

    /** Formats worth naming when one is rejected, because people have them. */
    val KNOWN_UNPLAYABLE: Map<String, String> = mapOf(
        "wma" to "Windows Media",
        "asf" to "Windows Media",
        "ra" to "RealAudio",
        "rm" to "RealAudio",
        "aiff" to "AIFF",
        "aif" to "AIFF",
        "ape" to "Monkey's Audio",
        "wv" to "WavPack",
        "mid" to "MIDI",
        "midi" to "MIDI",
    )

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    fun isPlayable(fileName: String): Boolean = extensionOf(fileName) in EXTENSIONS

    /** "Windows Media", or null when it is simply an unknown extension. */
    fun unplayableName(fileName: String): String? = KNOWN_UNPLAYABLE[extensionOf(fileName)]
}
