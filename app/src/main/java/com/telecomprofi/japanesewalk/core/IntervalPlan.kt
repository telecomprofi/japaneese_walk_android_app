package com.telecomprofi.japanesewalk.core

enum class Phase { WARMUP, SLOW, FAST, COOLDOWN }

/**
 * The Interval Walking Training ("Japanese walking") protocol.
 *
 * Default is the classic session: 5 sets of 3 minutes slow (~40% HRR) plus
 * 3 minutes fast (~70% HRR) = 30 minutes. Everything is configurable because
 * people build up to the full thing.
 */
data class IwtProtocol(
    val sets: Int = 5,
    val slowSec: Int = 3 * 60,
    val fastSec: Int = 3 * 60,
    val warmupSec: Int = 0,
    val cooldownSec: Int = 0,
    /** Top of the slow bout's range, as a share of heart-rate reserve. */
    val slowPct: Double = 0.40,
    /** Top of the fast bout's range, as a share of heart-rate reserve. */
    val fastPct: Double = 0.70,
    /**
     * How far below the top of a range still counts as on target, in points of
     * heart-rate reserve. 0.20 turns a 70% fast target into a 50-70% window,
     * which for a typical profile is roughly a 25 bpm-wide band.
     */
    val bandWidthPct: Double = 0.20,
    /** Target walking cadence in steps/min, used to pick music by BPM. */
    val slowCadenceSpm: Int = 105,
    val fastCadenceSpm: Int = 130,
    /** The published protocol starts with the slow bout, which doubles as warm-up. */
    val startWithSlow: Boolean = true,
) {
    fun targetBand(phase: Phase): TargetBand {
        fun floor(top: Double) = (top - bandWidthPct).coerceAtLeast(0.0)
        return when (phase) {
            // Both edges matter on the work bout.
            Phase.FAST -> TargetBand(floor(fastPct), fastPct)
            // Recovery bouts have a ceiling only: dropping lower is the point.
            Phase.SLOW -> TargetBand(floor(slowPct), slowPct, floorMatters = false)
            Phase.WARMUP -> TargetBand(floor(slowPct), slowPct, floorMatters = false)
            Phase.COOLDOWN -> TargetBand(0.0, slowPct, floorMatters = false)
        }
    }

    fun cadenceSpm(phase: Phase): Int = when (phase) {
        Phase.FAST -> fastCadenceSpm
        Phase.SLOW -> slowCadenceSpm
        Phase.WARMUP -> slowCadenceSpm - 5
        Phase.COOLDOWN -> slowCadenceSpm - 10
    }

    /** Minutes of actual interval work, excluding warm-up/cool-down. */
    val workMinutes: Int get() = sets * (slowSec + fastSec) / 60

    /** Short human-readable summary, stored with each saved session. */
    fun label(): String =
        "$sets x ${fastSec / 60}/${slowSec / 60} min @ " +
            "${(fastPct * 100).toInt()}/${(slowPct * 100).toInt()}% HRR"
}

data class Segment(
    val index: Int,
    val phase: Phase,
    /** 1-based set number; 0 for warm-up/cool-down. */
    val setNumber: Int,
    val startSec: Int,
    val durationSec: Int,
    val targetPct: Double,
    val cadenceSpm: Int,
) {
    val endSec: Int get() = startSec + durationSec
}

/** Expanded, immutable timeline for one protocol. */
class IntervalPlan(val protocol: IwtProtocol) {

    val segments: List<Segment> = buildList {
        var t = 0
        var i = 0
        if (protocol.warmupSec > 0) {
            add(
                Segment(
                    i++, Phase.WARMUP, 0, t, protocol.warmupSec,
                    protocol.targetBand(Phase.WARMUP).aimPct, protocol.cadenceSpm(Phase.WARMUP),
                )
            )
            t += protocol.warmupSec
        }
        val order = if (protocol.startWithSlow) {
            listOf(Phase.SLOW to protocol.slowSec, Phase.FAST to protocol.fastSec)
        } else {
            listOf(Phase.FAST to protocol.fastSec, Phase.SLOW to protocol.slowSec)
        }
        for (set in 1..protocol.sets) {
            for ((phase, dur) in order) {
                if (dur <= 0) continue
                add(
                    Segment(
                        i++, phase, set, t, dur,
                        protocol.targetBand(phase).aimPct, protocol.cadenceSpm(phase),
                    )
                )
                t += dur
            }
        }
        if (protocol.cooldownSec > 0) {
            add(
                Segment(
                    i++, Phase.COOLDOWN, 0, t, protocol.cooldownSec,
                    protocol.targetBand(Phase.COOLDOWN).aimPct, protocol.cadenceSpm(Phase.COOLDOWN),
                )
            )
            t += protocol.cooldownSec
        }
    }

    val totalSec: Int = segments.sumOf { it.durationSec }

    val fastSegments: Int = segments.count { it.phase == Phase.FAST }

    /** Segment covering [elapsedSec], or null once the session is over. */
    fun segmentAt(elapsedSec: Int): Segment? =
        segments.firstOrNull { elapsedSec >= it.startSec && elapsedSec < it.endSec }

    fun nextSegmentAfter(elapsedSec: Int): Segment? {
        val cur = segmentAt(elapsedSec) ?: return null
        return segments.getOrNull(cur.index + 1)
    }

    /** Seconds left in the current segment (0 when finished). */
    fun remainingInSegment(elapsedSec: Int): Int {
        val s = segmentAt(elapsedSec) ?: return 0
        return s.endSec - elapsedSec
    }

    fun isFinished(elapsedSec: Int): Boolean = elapsedSec >= totalSec

    /**
     * Fast bouts fully completed by [elapsedSec].
     *
     * Note the boundary: the fifth bout of the default protocol ends at exactly
     * 1800 s, so a session credited only to 1799 reports four sets, not five.
     */
    fun completedSets(elapsedSec: Int): Int =
        segments.filter { it.phase == Phase.FAST && it.endSec <= elapsedSec }
            .maxOfOrNull { it.setNumber } ?: 0

    /** Seconds of the plan still uncredited. Used to close out the last tick. */
    fun uncreditedSeconds(creditedSec: Int): Int = (totalSec - creditedSec).coerceAtLeast(0)

    fun overallProgress(elapsedSec: Int): Double =
        if (totalSec == 0) 0.0 else (elapsedSec.toDouble() / totalSec).coerceIn(0.0, 1.0)

    fun segmentProgress(elapsedSec: Int): Double {
        val s = segmentAt(elapsedSec) ?: return 1.0
        if (s.durationSec == 0) return 1.0
        return ((elapsedSec - s.startSec).toDouble() / s.durationSec).coerceIn(0.0, 1.0)
    }
}
