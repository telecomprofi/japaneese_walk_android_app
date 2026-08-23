package com.telecomprofi.japanesewalk.music

import kotlin.math.abs

data class MatchCandidate(val id: String, val bpm: Double)

data class MatchResult(
    val id: String,
    /** 0.5 / 1 / 2 — half-time, straight, double-time. */
    val multiplier: Double,
    /** Playback speed to apply (pitch-corrected time stretch). */
    val speed: Double,
    /** Perceived beats per minute after multiplier and stretch. */
    val effectiveBpm: Double,
    /** Remaining error against the requested cadence, in BPM. */
    val error: Double,
)

/**
 * Picks the track whose tempo best fits the cadence the interval engine is
 * asking for, then works out how much time-stretch is needed to lock onto it.
 *
 * Pure Kotlin so the selection rules can be unit-tested without a player.
 */
object TrackMatcher {

    /**
     * @param maxStretchPercent hard cap on time-stretch, e.g. 8 means 0.92x-1.08x.
     * @param allowOctave treat a 65 BPM track as a valid 130 spm match.
     * @param recentlyPlayed ids to avoid repeating, most recent first.
     */
    fun pick(
        candidates: List<MatchCandidate>,
        targetBpm: Int,
        maxStretchPercent: Int = 8,
        allowOctave: Boolean = true,
        recentlyPlayed: List<String> = emptyList(),
    ): MatchResult? {
        if (candidates.isEmpty() || targetBpm <= 0) return null
        val usable = candidates.filter { it.bpm > 0.0 }
        if (usable.isEmpty()) return null

        val maxStretch = (maxStretchPercent.coerceIn(0, 25)) / 100.0
        val multipliers = if (allowOctave) listOf(1.0, 2.0, 0.5) else listOf(1.0)

        var best: MatchResult? = null
        var bestScore = Double.MAX_VALUE

        for (c in usable) {
            for (mul in multipliers) {
                val base = c.bpm * mul
                if (base <= 0) continue
                val idealSpeed = targetBpm / base
                val speed = idealSpeed.coerceIn(1.0 - maxStretch, 1.0 + maxStretch)
                val effective = base * speed
                val error = abs(effective - targetBpm)
                // Prefer straight time over octave tricks, and avoid repeats.
                val octavePenalty = if (mul == 1.0) 0.0 else 1.5
                val repeatIndex = recentlyPlayed.indexOf(c.id)
                val repeatPenalty = when {
                    repeatIndex < 0 -> 0.0
                    else -> (recentlyPlayed.size - repeatIndex) * 4.0
                }
                val score = error + octavePenalty + repeatPenalty
                if (score < bestScore) {
                    bestScore = score
                    best = MatchResult(c.id, mul, speed, effective, error)
                }
            }
        }
        return best
    }
}
