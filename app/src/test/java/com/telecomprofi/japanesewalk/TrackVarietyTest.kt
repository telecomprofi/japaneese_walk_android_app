package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.music.MatchCandidate
import com.telecomprofi.japanesewalk.music.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The bug these are about: a thirteen-hundred track playlist that played the
 * same ten songs. The matcher returned the single closest tempo, so the only
 * thing varying the music was the eight-track "recently played" memory — which
 * is exactly how long the rotation turned out to be.
 */
class TrackVarietyTest {

    /** A library with tempos spread realistically around walking cadence. */
    private fun library(n: Int, seed: Int = 7): List<MatchCandidate> {
        val random = Random(seed)
        return (1..n).map { MatchCandidate("track-$it", 90.0 + random.nextDouble() * 80.0) }
    }

    @Test
    fun `a big library does not collapse to a handful of tracks`() {
        val candidates = library(1_300)
        val random = Random(1)
        val played = mutableListOf<String>()
        val recent = ArrayDeque<String>()

        repeat(200) {
            val pick = TrackMatcher.pickRandom(
                candidates = candidates,
                targetBpm = 120,
                recentlyPlayed = recent,
                random = random,
            )
            assertNotNull(pick)
            played += pick!!.id
            recent.addFirst(pick.id)
            while (recent.size > 250) recent.removeLast()
        }

        // The old code could only ever produce as many distinct tracks as the
        // memory was long. Two hundred picks must be two hundred songs.
        assertEquals(200, played.toSet().size)
    }

    @Test
    fun `the same cadence twice running gives different tracks`() {
        val candidates = library(400)
        val random = Random(3)
        val first = TrackMatcher.pickRandom(candidates, 118, random = random)!!
        val second = TrackMatcher.pickRandom(
            candidates, 118, recentlyPlayed = listOf(first.id), random = random,
        )!!
        assertTrue(first.id != second.id)
    }

    @Test
    fun `every pick still fits the cadence it was asked for`() {
        val candidates = library(500)
        val random = Random(11)
        repeat(100) {
            val pick = TrackMatcher.pickRandom(candidates, 125, random = random)!!
            // Within tolerance, or the library genuinely had nothing closer.
            assertTrue(
                "error ${pick.error} on a library that has close matches",
                pick.error <= TrackMatcher.TOLERANCE_BPM,
            )
        }
    }

    @Test
    fun `the pool is the good fits, not the whole library`() {
        val ranked = TrackMatcher.rank(library(1_000), targetBpm = 120)
        assertTrue(ranked.isNotEmpty())
        // rank() is ordered by fit, which is what makes taking the head sensible.
        assertTrue(ranked.zipWithNext().all { (a, b) -> a.error <= b.error })
    }

    @Test
    fun `recently played tracks are excluded, not merely penalised`() {
        val candidates = library(60)
        val ranked = TrackMatcher.rank(candidates, 120)
        val topTen = ranked.take(10).map { it.id }
        val random = Random(5)
        repeat(50) {
            val pick = TrackMatcher.pickRandom(
                candidates, 120, recentlyPlayed = topTen, random = random,
            )!!
            assertTrue("played a track that was in the recent list", pick.id !in topTen)
        }
    }

    @Test
    fun `when everything has been played the memory steps aside`() {
        val candidates = library(12)
        val all = candidates.map { it.id }
        val pick = TrackMatcher.pickRandom(candidates, 120, recentlyPlayed = all, random = Random(2))
        // Silence would be the wrong answer; playing something twice is not.
        assertNotNull(pick)
        assertTrue(pick!!.id in all)
    }

    @Test
    fun `a library with nothing close still plays something`() {
        // Every track is far too slow to reach 150 even at double time + stretch.
        val slow = listOf(MatchCandidate("a", 40.0), MatchCandidate("b", 43.0))
        val pick = TrackMatcher.pickRandom(slow, 150, random = Random(1))
        assertNotNull(pick)
        assertTrue(pick!!.error > TrackMatcher.TOLERANCE_BPM)
    }

    @Test
    fun `an empty library and a nonsense cadence are handled`() {
        assertNull(TrackMatcher.pickRandom(emptyList(), 120))
        assertNull(TrackMatcher.pickRandom(library(10), 0))
        assertNull(TrackMatcher.pickRandom(listOf(MatchCandidate("x", 0.0)), 120))
        assertTrue(TrackMatcher.rank(emptyList(), 120).isEmpty())
    }

    @Test
    fun `the single-best picker still works, for everything that relies on it`() {
        val candidates = listOf(
            MatchCandidate("far", 100.0),
            MatchCandidate("near", 119.0),
        )
        assertEquals("near", TrackMatcher.pick(candidates, 120)!!.id)
    }
}
