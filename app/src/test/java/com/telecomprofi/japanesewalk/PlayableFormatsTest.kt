package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.music.PlayableFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug: every other song skipped mid-walk, with the files plainly present.
 * They were Windows Media. The phone's own decoder reads WMA, so the analyser
 * measured tempo and loudness and the playlist looked healthy; the player has
 * no WMA extractor and refused every one of them.
 */
class PlayableFormatsTest {

    @Test
    fun `the formats the player can read are accepted`() {
        for (name in listOf(
            "song.mp3", "song.m4a", "song.flac", "song.ogg", "song.opus",
            "song.wav", "song.aac", "Song Name.MP3", "a.b.c.mp3",
        )) {
            assertTrue(name, PlayableFormats.isPlayable(name))
        }
    }

    @Test
    fun `Windows Media is rejected, however it is spelled`() {
        assertFalse(PlayableFormats.isPlayable("song.wma"))
        assertFalse(PlayableFormats.isPlayable("SONG.WMA"))
        assertFalse(PlayableFormats.isPlayable("Billy Joel - Uptown Girl.wma"))
    }

    @Test
    fun `other formats this player cannot read are rejected too`() {
        for (name in listOf("x.asf", "x.ra", "x.ape", "x.wv", "x.aiff", "x.mid")) {
            assertFalse(name, PlayableFormats.isPlayable(name))
        }
    }

    @Test
    fun `a rejected format is named, so the message can say why`() {
        assertEquals("Windows Media", PlayableFormats.unplayableName("song.wma"))
        assertEquals("RealAudio", PlayableFormats.unplayableName("song.ra"))
        assertEquals("MIDI", PlayableFormats.unplayableName("song.MID"))
        // An extension nobody has heard of gets the generic message instead.
        assertNull(PlayableFormats.unplayableName("song.zzz"))
        assertNull(PlayableFormats.unplayableName("song.mp3"))
    }

    @Test
    fun `a file with no extension is not assumed playable`() {
        assertFalse(PlayableFormats.isPlayable("song"))
        assertEquals("", PlayableFormats.extensionOf("song"))
    }

    @Test
    fun `the extension is the last one, not the first`() {
        assertEquals("mp3", PlayableFormats.extensionOf("my.wma.collection.mp3"))
        assertTrue(PlayableFormats.isPlayable("my.wma.collection.mp3"))
    }
}
