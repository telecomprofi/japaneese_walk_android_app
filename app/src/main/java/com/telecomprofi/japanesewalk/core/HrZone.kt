package com.telecomprofi.japanesewalk.core

import kotlin.math.roundToInt

/**
 * Bottom of the heart-rate gauge, in %HRR — below resting on purpose.
 * Declared at file scope because enum entries are constructed before the
 * companion object that re-exports it.
 */
private const val GAUGE_FLOOR_PCT = -0.20

/**
 * Five heart-rate training zones expressed in %HRR, colour-coded the way the
 * Apple-Watch-style "HR Zones" apps do it.
 *
 * The colour ramp is deliberately green -> yellow -> orange -> red for the
 * working zones, with a cool blue for pure recovery. The two IWT targets land
 * mid-zone rather than on a boundary, so the ring colour is a stable signal:
 *
 *   slow interval  ~40% HRR -> Z2 (green)
 *   fast interval  ~70% HRR -> Z4 (orange)
 *
 * Change the numbers here and the whole app (rings, chips, stats, notification)
 * follows; nothing else hard-codes zone colours or bounds.
 */
enum class HrZone(
    val idx: Int,
    val shortLabel: String,
    val label: String,
    /** Inclusive lower bound as a fraction of heart-rate reserve. */
    val lowerPct: Double,
    /** Exclusive upper bound as a fraction of heart-rate reserve. */
    val upperPct: Double,
    val argb: Long,
) {
    Z1(1, "Z1", "Easy", GAUGE_FLOOR_PCT, 0.45, 0xFF30D158),
    Z2(2, "Z2", "Brisk", 0.45, 0.58, 0xFFFFD60A),
    Z3(3, "Z3", "Hard", 0.58, 1.00, 0xFFFF9F0A),
    ;

    companion object {
        /**
         * Span of the 270-degree gauge, in %HRR.
         *
         * The floor is deliberately *below* resting: a heart rate of 63 with a
         * resting rate of 60 is only 2.5% of reserve, so a gauge starting at a
         * training threshold pins the needle at the origin for the whole warm-up.
         * Zone 1 starts here too, so every reading the gauge can show falls
         * inside a coloured zone — there is no dim, unreadable lead-in.
         */
        const val GAUGE_MIN_PCT = GAUGE_FLOOR_PCT
        const val GAUGE_MAX_PCT = 1.00

        val ordered: List<HrZone> = entries.sortedBy { it.idx }

        /** Zone for a %HRR value; anything below Z1 clamps to Z1, above Z3 to Z3. */
        fun forReservePct(pct: Double): HrZone = when {
            pct < Z2.lowerPct -> Z1
            pct < Z3.lowerPct -> Z2
            else -> Z3
        }

        fun forHr(hr: Int, profile: UserProfile): HrZone =
            forReservePct(profile.reservePctForHr(hr))

        /**
         * Where a heart rate sits along the gauge, as 0..1 across
         * [GAUGE_MIN_PCT]..[GAUGE_MAX_PCT]. Clamped.
         */
        fun gaugeFraction(hr: Int, profile: UserProfile): Double {
            val pct = profile.reservePctForHr(hr)
            val f = (pct - GAUGE_MIN_PCT) / (GAUGE_MAX_PCT - GAUGE_MIN_PCT)
            return f.coerceIn(0.0, 1.0)
        }

        fun gaugeFractionForPct(pct: Double): Double =
            ((pct - GAUGE_MIN_PCT) / (GAUGE_MAX_PCT - GAUGE_MIN_PCT)).coerceIn(0.0, 1.0)

        /** Concrete BPM bounds for each zone, for the gauge and the settings table. */
        fun bands(profile: UserProfile): List<ZoneBand> = ordered.map { z ->
            ZoneBand(
                zone = z,
                loBpm = profile.hrForReservePct(z.lowerPct),
                hiBpm = profile.hrForReservePct(z.upperPct),
                gaugeStart = gaugeFractionForPct(z.lowerPct),
                gaugeEnd = gaugeFractionForPct(z.upperPct),
            )
        }
    }
}

data class ZoneBand(
    val zone: HrZone,
    val loBpm: Int,
    val hiBpm: Int,
    val gaugeStart: Double,
    val gaugeEnd: Double,
) {
    val widthFraction: Double get() = gaugeEnd - gaugeStart
}

/**
 * The heart-rate window a bout is supposed to be walked in.
 *
 * This is a genuine **range**, not a point with a tolerance. The intensity you
 * set in Settings is the *top* of the range and the band extends downwards, so
 * a 70% HRR fast target with a 20-point band means "anywhere from 50% to 70%",
 * e.g. 124-146 bpm rather than "hit 146 exactly".
 *
 * Which edge matters depends on the bout:
 *
 *  - **Fast**: both. Too low is under-training, too high is over-cooking it.
 *  - **Slow**: only the ceiling. The slow bout is recovery, so drifting below
 *    the band is a success, not a fault, and is never coached against.
 */
data class TargetBand(
    val lowPct: Double,
    val highPct: Double,
    /** When false, sitting below [lowPct] counts as on target. */
    val floorMatters: Boolean = true,
    /** When false, sitting above [highPct] counts as on target. */
    val ceilingMatters: Boolean = true,
    /** How far past an edge before it becomes "way" off. */
    val gracePct: Double = 0.07,
) {
    /** Middle of the range: what the simulator aims for and what "100%" means. */
    val aimPct: Double get() = (lowPct + highPct) / 2

    val widthPct: Double get() = highPct - lowPct

    fun lowBpm(p: UserProfile): Int = p.hrForReservePct(lowPct)
    fun highBpm(p: UserProfile): Int = p.hrForReservePct(highPct)
    fun aimBpm(p: UserProfile): Int = p.hrForReservePct(aimPct)

    /** e.g. "124-146". */
    fun rangeText(p: UserProfile): String = "${lowBpm(p)}-${highBpm(p)}"

    fun contains(hr: Int, p: UserProfile): Boolean {
        val pct = p.reservePctForHr(hr)
        return pct >= lowPct && pct <= highPct
    }

    fun verdict(hr: Int, p: UserProfile): TargetVerdict {
        val pct = p.reservePctForHr(hr)
        return when {
            pct < lowPct - gracePct -> if (floorMatters) TargetVerdict.WAY_BELOW else TargetVerdict.ON_TARGET
            pct < lowPct -> if (floorMatters) TargetVerdict.BELOW else TargetVerdict.ON_TARGET
            pct <= highPct -> TargetVerdict.ON_TARGET
            pct <= highPct + gracePct -> if (ceilingMatters) TargetVerdict.ABOVE else TargetVerdict.ON_TARGET
            else -> if (ceilingMatters) TargetVerdict.WAY_ABOVE else TargetVerdict.ON_TARGET
        }
    }

    /** Where the current heart rate sits relative to the top of the range. */
    fun percentOfTarget(hr: Int, p: UserProfile): Int =
        if (highPct <= 0.0) 0
        else ((p.reservePctForHr(hr) / highPct) * 100).roundToInt().coerceIn(0, 300)
}

enum class TargetVerdict { WAY_BELOW, BELOW, ON_TARGET, ABOVE, WAY_ABOVE }
