package com.telecomprofi.japanesewalk.core

enum class Phase { WARMUP, SLOW, FAST, COOLDOWN }

/**
 * The Interval Walking Training ("Japanese walking") protocol.
 *
 * Default is the classic session: 5 sets of 3 minutes slow plus 3 minutes fast
 * = 30 minutes. Everything is configurable because people build up to the full
 * thing.
 *
 * Intensities are stored **twice**, once per [IntensityModel], because 70% of
 * heart-rate reserve and 70% of maximum are different heart rates and only one
 * of the two can be right at a time. [targetBand] picks the pair that matches
 * the live model, so targets and zone colours can never disagree.
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
    /**
     * The same three numbers in %HRmax, used when that model is selected.
     * 60/80 with a 12-point band is the conventional watch mapping of the
     * protocol's easy/brisk pair, and puts the slow bout on the Z1/Z2 line and
     * the fast bout inside Z3.
     */
    val slowMaxPct: Double = 0.60,
    val fastMaxPct: Double = 0.80,
    val bandWidthMaxPct: Double = 0.12,
    /**
     * How long the expected heart rate takes to travel between two bouts'
     * ranges, in seconds.
     *
     * A heart does not step. Asked to go from an easy walk to a brisk one it
     * takes a minute or more to arrive, and just as long to come back down, so a
     * rectangular target is wrong for most of every bout and the coach ends up
     * correcting a heart rate that is already on its way. The band therefore
     * ramps: at the start of a fast bout it is still the slow band and it slides
     * up over [transitionSec], which traces a saw-tooth rather than a square
     * wave and matches what the strap actually reports. 0 restores hard steps.
     */
    val transitionSec: Int = 60,
    /** Target walking cadence in steps/min, used to pick music by BPM. */
    val slowCadenceSpm: Int = 105,
    val fastCadenceSpm: Int = 130,
    /** The published protocol starts with the slow bout, which doubles as warm-up. */
    val startWithSlow: Boolean = true,
) {
    /** Top of the range for a bout, in the live model's units. */
    fun topPct(phase: Phase, model: IntensityModel): Double = when (model) {
        IntensityModel.HR_RESERVE -> if (phase == Phase.FAST) fastPct else slowPct
        IntensityModel.MAX_HR -> if (phase == Phase.FAST) fastMaxPct else slowMaxPct
    }

    fun bandWidth(model: IntensityModel): Double = when (model) {
        IntensityModel.HR_RESERVE -> bandWidthPct
        IntensityModel.MAX_HR -> bandWidthMaxPct
    }

    fun targetBand(phase: Phase, model: IntensityModel): TargetBand {
        val width = bandWidth(model)
        val floorOf = { top: Double -> (top - width).coerceAtLeast(0.0) }
        // Grace scales with the band: a 12-point band should not be judged with
        // the same 7-point slack as a 20-point one.
        val grace = (width * 0.35).coerceIn(0.03, 0.08)
        return when (phase) {
            // Both edges matter on the work bout.
            Phase.FAST -> {
                val top = topPct(Phase.FAST, model)
                TargetBand(floorOf(top), top, gracePct = grace)
            }
            // Recovery bouts have a ceiling only: dropping lower is the point.
            Phase.SLOW, Phase.WARMUP -> {
                val top = topPct(Phase.SLOW, model)
                TargetBand(floorOf(top), top, floorMatters = false, gracePct = grace)
            }
            // Floor has to be model-relative: 0.0 is resting heart rate in the
            // reserve scale but literally zero bpm in the %HRmax one, which
            // would draw a target window covering the whole dial.
            Phase.COOLDOWN -> TargetBand(
                HrZone.gaugeMin(model).coerceAtLeast(0.0).coerceAtMost(topPct(Phase.SLOW, model)),
                topPct(Phase.SLOW, model),
                floorMatters = false,
                gracePct = grace,
            )
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
    fun label(model: IntensityModel = IntensityModel.HR_RESERVE): String {
        val units = if (model == IntensityModel.HR_RESERVE) "% HRR" else "% HRmax"
        val fast = (topPct(Phase.FAST, model) * 100).toInt()
        val slow = (topPct(Phase.SLOW, model) * 100).toInt()
        return "$sets x ${fastSec / 60}/${slowSec / 60} min @ $fast/$slow$units"
    }
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
class IntervalPlan(
    val protocol: IwtProtocol,
    val model: IntensityModel = IntensityModel.HR_RESERVE,
) {

    val segments: List<Segment> = buildList {
        var t = 0
        var i = 0
        if (protocol.warmupSec > 0) {
            add(
                Segment(
                    i++, Phase.WARMUP, 0, t, protocol.warmupSec,
                    protocol.targetBand(Phase.WARMUP, model).aimPct, protocol.cadenceSpm(Phase.WARMUP),
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
                        protocol.targetBand(phase, model).aimPct, protocol.cadenceSpm(phase),
                    )
                )
                t += dur
            }
        }
        if (protocol.cooldownSec > 0) {
            add(
                Segment(
                    i++, Phase.COOLDOWN, 0, t, protocol.cooldownSec,
                    protocol.targetBand(Phase.COOLDOWN, model).aimPct, protocol.cadenceSpm(Phase.COOLDOWN),
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

    /** The band the current bout prescribes, with no transition applied. */
    fun bandFor(phase: Phase): TargetBand = protocol.targetBand(phase, model)

    /**
     * The band being judged **right now**, ramped across the bout change.
     *
     * Within the first [IwtProtocol.transitionSec] of a bout the range is a
     * blend of the previous bout's and this one's, so the expected heart rate
     * rises and falls the way a real one does instead of jumping. Both the voice
     * coach and the on-target accounting read this, which is what stops "well
     * under target" firing at second three of a fast bout.
     */
    fun bandAt(elapsedSec: Int): TargetBand? {
        val cur = segmentAt(elapsedSec) ?: return null
        val target = bandFor(cur.phase)
        // Never ramp for longer than the bout lasts, or a 1-minute bout with a
        // 60 s transition would never once be judged against its real target —
        // and the next bout would start lerping down from a band that was never
        // actually in force, stepping the ceiling *up* as you ease off.
        // durationSec - 1, not durationSec: segmentAt() is exclusive of endSec,
        // so the last second a bout is ever evaluated at is startSec + dur - 1.
        // Clamping to dur exactly would mean the settled target arrives one
        // second after the bout's last tick — never once actually in force.
        val ramp = protocol.transitionSec.coerceAtMost((cur.durationSec - 1).coerceAtLeast(0))
        if (ramp <= 0) return target
        val prev = segments.getOrNull(cur.index - 1) ?: return target
        if (prev.phase == cur.phase) return target
        val into = (elapsedSec - cur.startSec).coerceAtLeast(0)
        if (into >= ramp) return target
        return bandFor(prev.phase).lerpTo(target, into.toDouble() / ramp)
    }

    /** Seconds left in the current segment (0 when finished). */
    fun remainingInSegment(elapsedSec: Int): Int {
        val s = segmentAt(elapsedSec) ?: return 0
        return s.endSec - elapsedSec
    }

    /** Seconds spent so far in the current segment. */
    fun elapsedInSegment(elapsedSec: Int): Int {
        val s = segmentAt(elapsedSec) ?: return 0
        return (elapsedSec - s.startSec).coerceAtLeast(0)
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
