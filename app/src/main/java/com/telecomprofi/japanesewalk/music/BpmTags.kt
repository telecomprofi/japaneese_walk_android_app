package com.telecomprofi.japanesewalk.music

/**
 * The BPM the file already knows about.
 *
 * Measuring tempo from audio is a genuinely hard signal-processing problem and
 * it gets commercial dance music wrong in a characteristic way: it locks onto
 * two-thirds or half of the real tempo, because the offbeat hats and the
 * sub-beat grid correlate just as well as the beat does. Meanwhile the file
 * itself very often carries the right answer, written by whoever produced or
 * catalogued it — iTunes, Traktor, Mixed In Key and Beatport downloads all
 * write a BPM tag. Reading it costs a few kilobytes and is exactly right when
 * it is there.
 *
 * Two containers cover almost every library: ID3v2 in MP3 (the `TBPM` frame)
 * and the MP4 family in M4A/AAC (the `tmpo` atom). Both are parsed here rather
 * than through a library so the whole thing can be tested on the JVM.
 */
object BpmTags {

    /** Nothing outside this is a plausible musical tempo for a whole track. */
    const val MIN_BPM = 40.0
    const val MAX_BPM = 250.0

    /** How much of the file needs reading before giving up on a tag. */
    const val HEAD_BYTES = 256 * 1024

    fun isPlausible(bpm: Double): Boolean = bpm in MIN_BPM..MAX_BPM

    /**
     * Read a BPM tag from the head of a file.
     *
     * @param head the first bytes of the file; [HEAD_BYTES] is plenty for ID3,
     *        which sits at the front, and for the MP4 `moov` atom when it has
     *        been placed at the front (as streaming-ready files do).
     */
    fun fromHeader(head: ByteArray): Double? =
        fromId3(head) ?: fromMp4(head)

    // ---------------------------------------------------------------- ID3v2 --

    /**
     * ID3v2.2/2.3/2.4, as written at the very start of an MP3.
     *
     * Layout: "ID3", two version bytes, flags, then a syncsafe size; frames
     * follow, each a four-character id (three in v2.2), a size, and flags. Only
     * `TBPM` is wanted, and its payload is an encoding byte followed by text.
     */
    fun fromId3(bytes: ByteArray): Double? {
        if (bytes.size < 10) return null
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) {
            return null
        }
        val major = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = syncsafe(bytes, 6)
        if (tagSize <= 0) return null

        var pos = 10
        // An extended header, when present, sits between the header and the
        // first frame and declares its own length.
        if (major >= 3 && (flags and 0x40) != 0) {
            if (pos + 4 > bytes.size) return null
            val extended = if (major == 4) syncsafe(bytes, pos) else int32(bytes, pos) + 4
            pos += extended.coerceAtLeast(0)
        }

        val idLen = if (major == 2) 3 else 4
        val sizeLen = if (major == 2) 3 else 4
        val flagLen = if (major == 2) 0 else 2
        val end = (10 + tagSize).coerceAtMost(bytes.size)

        while (pos + idLen + sizeLen + flagLen <= end) {
            val id = String(bytes, pos, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') return null          // padding: no more frames
            val size = when {
                major == 2 -> int24(bytes, pos + idLen)
                major == 4 -> syncsafe(bytes, pos + idLen)
                else -> int32(bytes, pos + idLen)
            }
            if (size <= 0 || size > end) return null
            val body = pos + idLen + sizeLen + flagLen
            if (body + size > end) return null
            // TBPM in v2.3/2.4, TBP in the older v2.2 three-character scheme.
            if (id == "TBPM" || id == "TBP") {
                return parseText(bytes, body, size)?.let { readBpm(it) }
            }
            pos = body + size
        }
        return null
    }

    private fun parseText(bytes: ByteArray, offset: Int, size: Int): String? {
        if (size < 1) return null
        val encoding = bytes[offset].toInt() and 0xFF
        val from = offset + 1
        val len = size - 1
        if (len <= 0 || from + len > bytes.size) return null
        val charset = when (encoding) {
            1 -> Charsets.UTF_16          // with BOM
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(bytes, from, len, charset)
    }

    // ------------------------------------------------------------------ MP4 --

    /**
     * The `tmpo` atom inside `moov/udta/meta/ilst`, as iTunes writes it: a
     * `data` box whose payload is a big-endian integer.
     *
     * Rather than walking the box tree — which costs a full parser and several
     * container quirks — this looks for the four bytes `tmpo` and reads the
     * `data` box that follows. A false positive would have to be the literal
     * text "tmpo" followed by a well-formed data box carrying a plausible
     * tempo, which is not a thing that happens by accident.
     */
    fun fromMp4(bytes: ByteArray): Double? {
        val marker = byteArrayOf('t'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'o'.code.toByte())
        var at = indexOf(bytes, marker, 0)
        while (at >= 0) {
            // tmpo | data-box-size(4) | "data"(4) | version+flags(4) | reserved(4) | value
            val dataBox = at + 4
            if (dataBox + 16 <= bytes.size &&
                bytes[dataBox + 4] == 'd'.code.toByte() &&
                bytes[dataBox + 5] == 'a'.code.toByte() &&
                bytes[dataBox + 6] == 't'.code.toByte() &&
                bytes[dataBox + 7] == 'a'.code.toByte()
            ) {
                val size = int32(bytes, dataBox)
                val valueAt = dataBox + 16
                val valueLen = size - 16
                if (valueLen in 1..4 && valueAt + valueLen <= bytes.size) {
                    var v = 0L
                    for (i in 0 until valueLen) v = (v shl 8) or (bytes[valueAt + i].toLong() and 0xFF)
                    val bpm = v.toDouble()
                    if (isPlausible(bpm)) return bpm
                }
            }
            at = indexOf(bytes, marker, at + 1)
        }
        return null
    }

    // --------------------------------------------------------------- shared --

    /** "128", "128.5", "128 BPM", "0128" — all of them mean the same thing. */
    fun readBpm(text: String): Double? {
        val cleaned = text.trim().trimEnd('\u0000').trim()
        if (cleaned.isEmpty()) return null
        val number = Regex("""\d+(?:[.,]\d+)?""").find(cleaned)?.value ?: return null
        val bpm = number.replace(',', '.').toDoubleOrNull() ?: return null
        return if (isPlausible(bpm)) bpm else null
    }

    private fun syncsafe(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return -1
        // Seven bits per byte: the eighth is always zero so the size can never
        // look like an MPEG sync word.
        return ((b[at].toInt() and 0x7F) shl 21) or
            ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or
            (b[at + 3].toInt() and 0x7F)
    }

    private fun int32(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return -1
        return ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)
    }

    private fun int24(b: ByteArray, at: Int): Int {
        if (at + 3 > b.size) return -1
        return ((b[at].toInt() and 0xFF) shl 16) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            (b[at + 2].toInt() and 0xFF)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
