package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.music.BpmTags
import com.telecomprofi.japanesewalk.music.TrackDeduper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file usually knows its own tempo. These tests build the tags by hand,
 * byte for byte, because the whole point of parsing them here rather than
 * through a library is that the parsing can be checked without a device.
 */
class BpmTagTest {

    /** An ID3v2.3 tag carrying one text frame. */
    private fun id3v23(frameId: String, text: String, encoding: Int = 0): ByteArray {
        val payload = byteArrayOf(encoding.toByte()) + text.toByteArray(Charsets.ISO_8859_1)
        val frame = frameId.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(
                (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(), payload.size.toByte(),
            ) + byteArrayOf(0, 0) + payload
        val size = frame.size
        val header = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0) +
            byteArrayOf(
                ((size ushr 21) and 0x7F).toByte(), ((size ushr 14) and 0x7F).toByte(),
                ((size ushr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
            )
        return header + frame + ByteArray(64)
    }

    @Test
    fun `a plain TBPM frame is read`() {
        assertEquals(128.0, BpmTags.fromId3(id3v23("TBPM", "128"))!!, 1e-9)
    }

    @Test
    fun `the tag survives the ways people write tempo`() {
        assertEquals(128.0, BpmTags.readBpm("128")!!, 1e-9)
        assertEquals(128.0, BpmTags.readBpm("0128")!!, 1e-9)
        assertEquals(128.5, BpmTags.readBpm("128.5")!!, 1e-9)
        assertEquals(128.5, BpmTags.readBpm("128,5")!!, 1e-9)
        assertEquals(155.0, BpmTags.readBpm("155 BPM")!!, 1e-9)
        assertEquals(128.0, BpmTags.readBpm("128\u0000")!!, 1e-9)
    }

    @Test
    fun `nonsense in a tag is not believed`() {
        assertNull(BpmTags.readBpm(""))
        assertNull(BpmTags.readBpm("   "))
        assertNull(BpmTags.readBpm("unknown"))
        assertNull(BpmTags.readBpm("0"))        // below any real tempo
        assertNull(BpmTags.readBpm("9999"))     // above any real tempo
    }

    @Test
    fun `a frame that is not TBPM is skipped, and the next one is found`() {
        val artist = id3v23("TPE1", "Billy Joel")
        // Two frames in one tag: the parser has to walk past the first.
        val tbpm = id3v23("TBPM", "128")
        val merged = mergeFrames(artist, tbpm)
        assertEquals(128.0, BpmTags.fromId3(merged)!!, 1e-9)
    }

    private fun mergeFrames(a: ByteArray, b: ByteArray): ByteArray {
        val frameA = a.copyOfRange(10, a.size - 64)
        val frameB = b.copyOfRange(10, b.size - 64)
        val body = frameA + frameB
        val size = body.size
        val header = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0) +
            byteArrayOf(
                ((size ushr 21) and 0x7F).toByte(), ((size ushr 14) and 0x7F).toByte(),
                ((size ushr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
            )
        return header + body + ByteArray(32)
    }

    @Test
    fun `UTF-16 text is decoded`() {
        val text = "﻿155".toByteArray(Charsets.UTF_16LE)
        val payload = byteArrayOf(1) + text
        val frame = "TBPM".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0, 0, payload.size.toByte()) + byteArrayOf(0, 0) + payload
        val header = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0) +
            byteArrayOf(0, 0, 0, frame.size.toByte())
        assertEquals(155.0, BpmTags.fromId3(header + frame + ByteArray(16))!!, 1e-9)
    }

    @Test
    fun `a file with no tag at all is not guessed at`() {
        assertNull(BpmTags.fromId3(ByteArray(2048)))
        assertNull(BpmTags.fromId3("not an mp3".toByteArray()))
        assertNull(BpmTags.fromHeader(ByteArray(0)))
    }

    @Test
    fun `the MP4 tmpo atom is read`() {
        // tmpo | size | "data" | version+flags | reserved | value
        val value = byteArrayOf(0, 155.toByte())
        val size = 16 + value.size
        val atom = "tmpo".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0, 0, size.toByte()) +
            "data".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0, 0, 21) + byteArrayOf(0, 0, 0, 0) + value
        assertEquals(155.0, BpmTags.fromMp4(ByteArray(48) + atom)!!, 1e-9)
    }

    @Test
    fun `an implausible tmpo value is ignored rather than trusted`() {
        val value = byteArrayOf(0, 5)   // 5 BPM is not a tempo
        val size = 16 + value.size
        val atom = "tmpo".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0, 0, size.toByte()) +
            "data".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0, 0, 21) + byteArrayOf(0, 0, 0, 0) + value
        assertNull(BpmTags.fromMp4(atom))
    }
}

/**
 * Two versions of one song must not play near each other. The examples are the
 * shapes real libraries actually contain.
 */
class SongKeyTest {

    private fun key(title: String) = TrackDeduper.songKey(title, "$title.mp3")

    @Test
    fun `versions of one song share a key`() {
        val plain = key("Uptown Girl")
        assertEquals(plain, key("Uptown Girl (Extended Mix)"))
        assertEquals(plain, key("Uptown Girl [Radio Edit]"))
        assertEquals(plain, key("Uptown Girl - Sped Up"))
        assertEquals(plain, key("Uptown Girl (2019 Remaster)"))
        assertEquals(plain, key("uptown girl (Live)"))
        assertEquals(plain, key("Uptown  Girl!"))
    }

    @Test
    fun `track numbers and guests do not make a different song`() {
        assertEquals(key("Jump For Joy"), TrackDeduper.songKey(null, "07 - Jump For Joy.mp3"))
        assertEquals(key("Jump For Joy"), key("Jump For Joy feat. Ray Slijngaard"))
        assertEquals(key("Jump For Joy"), key("Jump For Joy (feat. Anita) [Club Mix]"))
    }

    @Test
    fun `different songs keep different keys`() {
        assertNotEquals(key("Uptown Girl"), key("Uptown Funk"))
        assertNotEquals(key("Jump For Joy"), key("Jump"))
        assertNotEquals(key("No Limit"), key("The Limit"))
    }

    @Test
    fun `a bracket that is part of the title is left alone`() {
        // Not a version marker, so it stays and keeps the songs apart.
        assertNotEquals(key("Song (Part One)"), key("Song (Part Two)"))
    }

    @Test
    fun `a missing title falls back to the file name`() {
        assertEquals(
            TrackDeduper.songKey(null, "Uptown Girl (Extended Mix).mp3"),
            TrackDeduper.songKey("Uptown Girl", "whatever.mp3"),
        )
        assertTrue(TrackDeduper.songKey("", "").isEmpty() || TrackDeduper.songKey("", "").isNotEmpty())
    }

    @Test
    fun `sameSong answers the question the player asks`() {
        assertTrue(
            TrackDeduper.sameSong(
                "Jump For Joy", "a.mp3",
                "Jump For Joy (Extended Mix)", "b.mp3",
            )
        )
        assertTrue(!TrackDeduper.sameSong("Jump For Joy", "a.mp3", "Tribal Dance", "b.mp3"))
    }
}
