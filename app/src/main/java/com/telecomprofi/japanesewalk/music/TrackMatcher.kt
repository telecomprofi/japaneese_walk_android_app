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

    /** Residual error, in BPM, at which a track still counts as "fits". */
    const val TOLERANCE_BPM = 6.0

    /** How many of the best-fitting tracks are worth choosing between. */
    const val POOL_SIZE = 24


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

    /**
     * Every track that could serve this cadence, best fit first.
     *
     * The single best track is rarely what you want. A library of thirteen
     * hundred songs still has one mathematically closest match to 120 spm, and
     * picking it every time is why the same ten tracks came round and round: the
     * repeat penalty only pushed the winner down far enough for the runner-up to
     * take over, so the rotation was as long as the memory and no longer.
     */
    fun rank(
        candidates: List<MatchCandidate>,
        targetBpm: Int,
        maxStretchPercent: Int = 8,
        allowOctave: Boolean = true,
    ): List<MatchResult> {
        if (candidates.isEmpty() || targetBpm <= 0) return emptyList()
        val maxStretch = (maxStretchPercent.coerceIn(0, 25)) / 100.0
        val multipliers = if (allowOctave) listOf(1.0, 2.0, 0.5) else listOf(1.0)

        return candidates.mapNotNull { c ->
            if (c.bpm <= 0.0) return@mapNotNull null
            var best: MatchResult? = null
            var bestScore = Double.MAX_VALUE
            for (mul in multipliers) {
                val base = c.bpm * mul
                if (base <= 0) continue
                val speed = (targetBpm / base).coerceIn(1.0 - maxStretch, 1.0 + maxStretch)
                val effective = base * speed
                val error = abs(effective - targetBpm)
                // Straight time is still preferred over an octave trick, all
                // else being equal — it just no longer decides the whole walk.
                val score = error + if (mul == 1.0) 0.0 else 1.5
                if (score < bestScore) {
                    bestScore = score
                    best = MatchResult(c.id, mul, speed, effective, error)
                }
            }
            best
        }.sortedBy { it.error }
    }

    /**
     * One of the tracks that fit, chosen at random.
     *
     * "Fit" means within [TOLERANCE_BPM] of the cadence after stretching; if
     * nothing is that close the best [POOL_SIZE] are used instead, so a playlist
     * with no good match still varies. Anything in [recentlyPlayed] is excluded
     * outright rather than penalised — a penalty can always be outweighed, and
     * that is precisely how a short rotation forms.
     */
    fun pickRandom(
        candidates: List<MatchCandidate>,
        targetBpm: Int,
        maxStretchPercent: Int = 8,
        allowOctave: Boolean = true,
        recentlyPlayed: Collection<String> = emptyList(),
        toleranceBpm: Double = TOLERANCE_BPM,
        poolSize: Int = POOL_SIZE,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): MatchResult? {
        val ranked = rank(candidates, targetBpm, maxStretchPercent, allowOctave)
        if (ranked.isEmpty()) return null
        val recent = recentlyPlayed.toSet()
        // Fresh tracks only — unless everything has been played, in which case
        // the memory has served its purpose and is set aside for this pick.
        val fresh = ranked.filterNot { it.id in recent }.ifEmpty { ranked }
        val close = fresh.filter { it.error <= toleranceBpm }
        val pool = (if (close.isNotEmpty()) close else fresh).take(poolSize.coerceAtLeast(1))
        return pool[random.nextInt(pool.size)]
    }
}
