package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.music.DedupeCandidate
import com.telecomprofi.japanesewalk.music.MatchCandidate
import com.telecomprofi.japanesewalk.music.TempoEstimator
import com.telecomprofi.japanesewalk.music.TrackDeduper
import com.telecomprofi.japanesewalk.music.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class TempoAndLibraryTest {

    /** Synthetic drum loop: kick on the beat, optional hi-hat on the eighths. */
    private fun clickTrack(bpm: Double, seconds: Double, hihat: Boolean, rnd: Random): FloatArray {
        val sr = TempoEstimator.ANALYSIS_SR
        val n = (sr * seconds).toInt()
        val out = FloatArray(n)
        val beatSamples = 60.0 / bpm * sr
        var beat = 0
        while (true) {
            val start = (beat * beatSamples).toInt()
            if (start >= n) break
            for (i in 0 until (0.16 * sr).toInt()) {
                val idx = start + i
                if (idx >= n) break
                val t = i / sr.toDouble()
                out[idx] += (0.9 * exp(-25.0 * t) * sin(2 * Math.PI * 62 * t)).toFloat()
                out[idx] += (0.35 * exp(-70.0 * t) * (rnd.nextDouble() * 2 - 1)).toFloat()
            }
            if (hihat) {
                val off = start + (beatSamples / 2).toInt()
                for (i in 0 until (0.04 * sr).toInt()) {
                    val idx = off + i
                    if (idx >= n) break
                    val t = i / sr.toDouble()
                    out[idx] += (0.22 * exp(-120.0 * t) * (rnd.nextDouble() * 2 - 1)).toFloat()
                }
            }
            beat++
        }
        for (i in 0 until n) {
            val t = i / sr.toDouble()
            out[i] += (0.05 * sin(2 * Math.PI * 220 * t)).toFloat()
            out[i] += (0.01 * (rnd.nextDouble() * 2 - 1)).toFloat()
            out[i] = out[i].coerceIn(-1f, 1f)
        }
        return out
    }

    @Test
    fun `tempo is recovered from synthetic click tracks`() {
        val rnd = Random(42)
        listOf(92.0, 105.0, 118.0, 128.0, 136.0, 150.0).forEach { bpm ->
            val result = TempoEstimator.estimate(clickTrack(bpm, 45.0, hihat = false, rnd = rnd))
            assertEquals("kick-only at $bpm gave ${result.bpm}", bpm, result.bpm, 2.0)
            assertTrue("confidence ${result.confidence}", result.isUsable)
        }
    }

    @Test
    fun `hi-hats do not push the estimate to double time`() {
        val rnd = Random(7)
        listOf(100.0, 124.0, 140.0).forEach { bpm ->
            val result = TempoEstimator.estimate(clickTrack(bpm, 45.0, hihat = true, rnd = rnd))
            assertTrue(
                "kick+hihat at $bpm gave ${result.bpm}",
                abs(result.bpm - bpm) < 2.5 || abs(result.bpm - 2 * bpm) < 3.0,
            )
        }
    }

    @Test
    fun `silence and short buffers are reported as unusable`() {
        val silence = TempoEstimator.estimate(FloatArray(TempoEstimator.ANALYSIS_SR * 5))
        assertTrue(!silence.isUsable)
        assertEquals(0.0, TempoEstimator.estimate(FloatArray(500)).bpm, 1e-9)
    }

    @Test
    fun `octave folding keeps tempi in the walking range`() {
        assertEquals(120.0, TempoEstimator.fold(60.0), 1e-9)
        assertEquals(100.0, TempoEstimator.fold(200.0), 1e-9)
        assertEquals(130.0, TempoEstimator.fold(130.0), 1e-9)
    }

    @Test
    fun `duplicate copies of a song collapse to the shallowest one`() {
        // The exact shape a doubleTwist sync leaves behind.
        val candidates = listOf(
            DedupeCandidate("uri-a", "Praise You.mp3", "Android/data/com.doubleTwist/files/Music/Praise You.mp3", 213_000),
            DedupeCandidate("uri-b", "Praise You.mp3", "Music/Fatboy Slim/Better Living/Praise You.mp3", 213_000),
            DedupeCandidate("uri-c", "Praise You.mp3", "Praise You.mp3", 213_000),
            DedupeCandidate("uri-d", "Around The World.mp3", "Music/Daft Punk/Homework/Around The World.mp3", 428_000),
        )
        val distinct = TrackDeduper.distinct(candidates)
        assertEquals(2, distinct.size)
        assertEquals("uri-c", distinct.first().uri)
        assertEquals(2, TrackDeduper.duplicateCount(candidates))
    }

    @Test
    fun `same name but a different length is a different song`() {
        val candidates = listOf(
            DedupeCandidate("a", "Intro.mp3", "A/Intro.mp3", 30_000),
            DedupeCandidate("b", "Intro.mp3", "B/Intro.mp3", 95_000),
        )
        assertEquals(2, TrackDeduper.distinct(candidates).size)
    }

    @Test
    fun `unknown durations still collapse by name`() {
        val candidates = listOf(
            DedupeCandidate("a", "Song.mp3", "Music/Song.mp3", 0),
            DedupeCandidate("b", "song.MP3", "Android/data/x/Song.mp3", 0),
        )
        assertEquals(1, TrackDeduper.distinct(candidates).size)
        assertEquals("a", TrackDeduper.distinct(candidates).first().uri)
    }

    @Test
    fun `private app directories are skipped outright`() {
        assertTrue(TrackDeduper.isSkippedDirectory("Android"))
        assertTrue(TrackDeduper.isSkippedDirectory("android"))
        assertTrue(TrackDeduper.isSkippedDirectory(".thumbnails"))
        assertTrue(!TrackDeduper.isSkippedDirectory("Music"))
        assertTrue(!TrackDeduper.isSkippedDirectory("Fatboy Slim"))
    }

    @Test
    fun `matcher picks the closest tempo and stretches onto the target`() {
        val library = listOf(
            MatchCandidate("slowish", 96.0),
            MatchCandidate("mid", 118.0),
            MatchCandidate("fast", 132.0),
        )
        val fast = TrackMatcher.pick(library, targetBpm = 130)!!
        assertEquals("fast", fast.id)
        assertEquals(130.0, fast.effectiveBpm, 0.01)
        assertEquals("slowish", TrackMatcher.pick(library, targetBpm = 105)!!.id)
    }

    @Test
    fun `time stretch is capped and the residual error reported`() {
        val capped = TrackMatcher.pick(
            listOf(MatchCandidate("only", 100.0)),
            targetBpm = 140,
            maxStretchPercent = 8,
        )!!
        assertEquals(1.08, capped.speed, 1e-9)
        assertEquals(32.0, capped.error, 0.01)
    }

    @Test
    fun `half and double time matches can be switched off`() {
        val octave = TrackMatcher.pick(listOf(MatchCandidate("h", 66.0)), 130, allowOctave = true)!!
        assertEquals(2.0, octave.multiplier, 1e-9)
        val straight = TrackMatcher.pick(listOf(MatchCandidate("h", 66.0)), 130, allowOctave = false)!!
        assertEquals(1.0, straight.multiplier, 1e-9)
    }

    @Test
    fun `recently played tracks are avoided and empty libraries return null`() {
        val library = listOf(MatchCandidate("a", 130.0), MatchCandidate("b", 128.0))
        assertEquals("b", TrackMatcher.pick(library, 130, recentlyPlayed = listOf("a"))!!.id)
        assertNull(TrackMatcher.pick(emptyList(), 130))
        assertNull(TrackMatcher.pick(listOf(MatchCandidate("x", 0.0)), 130))
    }
}
