package com.telecomprofi.japanesewalk.core

import kotlin.math.roundToInt

/**
 * Bottom of the gauge in each scale — below anything you can train at, on
 * purpose, so a resting heart rate still moves the needle.
 *
 * Declared at file scope because enum entries are constructed before the
 * companion object that re-exports them.
 */
private const val RESERVE_FLOOR = -0.20
private const val MAX_HR_FLOOR = 0.35

/**
 * Three heart-rate training zones, carrying bounds for **both** intensity
 * models. Which pair is live depends on [UserProfile.intensityModel]; nothing
 * else in the app hard-codes a zone bound or colour.
 *
 * Three rather than five, and no red: this app has exactly two prescribed
 * intensities, so the useful question is "below, in, or above the work zone",
 * and a red band people are told to avoid adds nothing.
 *
 * Where the two IWT targets land:
 *
 *   HR_RESERVE   slow 40% -> Z1/Z2 boundary   fast 70% -> Z3
 *   MAX_HR       slow 60% -> Z1/Z2 boundary   fast 80% -> Z3
 *
 * The %HRmax boundaries (60 / 70) are the ones Samsung Health, Garmin and most
 * watches use, which is the whole point of offering the model.
 */
enum class HrZone(
    val idx: Int,
    val shortLabel: String,
    val label: String,
    /** Inclusive lower bound as a fraction of heart-rate reserve. */
    val reserveLow: Double,
    /** Exclusive upper bound as a fraction of heart-rate reserve. */
    val reserveHigh: Double,
    /** Inclusive lower bound as a fraction of maximum heart rate. */
    val maxLow: Double,
    /** Exclusive upper bound as a fraction of maximum heart rate. */
    val maxHigh: Double,
    val argb: Long,
) {
    Z1(1, "Z1", "Easy", RESERVE_FLOOR, 0.45, MAX_HR_FLOOR, 0.60, 0xFF30D158),
    Z2(2, "Z2", "Brisk", 0.45, 0.58, 0.60, 0.70, 0xFFFFD60A),
    Z3(3, "Z3", "Hard", 0.58, 1.00, 0.70, 1.00, 0xFFFF9F0A),
    ;

    fun low(model: IntensityModel): Double =
        if (model == IntensityModel.HR_RESERVE) reserveLow else maxLow

    fun high(model: IntensityModel): Double =
        if (model == IntensityModel.HR_RESERVE) reserveHigh else maxHigh

    companion object {

        fun gaugeMin(model: IntensityModel): Double =
            if (model == IntensityModel.HR_RESERVE) RESERVE_FLOOR else MAX_HR_FLOOR

        fun gaugeMax(model: IntensityModel): Double = 1.00

        val ordered: List<HrZone> = entries.sortedBy { it.idx }

        /** Zone for an intensity in the given model; clamps at both ends. */
        fun forIntensity(pct: Double, model: IntensityModel): HrZone = when {
            pct < Z2.low(model) -> Z1
            pct < Z3.low(model) -> Z2
            else -> Z3
        }

        /** Kept for the Karvonen-only callers and the maths tests. */
        fun forReservePct(pct: Double): HrZone = forIntensity(pct, IntensityModel.HR_RESERVE)

        fun forHr(hr: Int, profile: UserProfile): HrZone =
            forIntensity(profile.intensityForHr(hr), profile.intensityModel)

        /**
         * Where a heart rate sits along the gauge, as 0..1 across the live
         * model's floor..ceiling. Clamped.
         *
         * The floor is deliberately below anything trainable — below resting in
         * the Karvonen scale, 35% of maximum in the %HRmax one — because a gauge
         * that starts at a training threshold pins the needle at the origin for
         * the whole warm-up. Zone 1 starts at the floor too, so every reading the
         * gauge can show falls inside a coloured band.
         */
        fun gaugeFraction(hr: Int, profile: UserProfile): Double =
            gaugeFractionForPct(profile.intensityForHr(hr), profile.intensityModel)

        fun gaugeFractionForPct(pct: Double, model: IntensityModel): Double {
            val lo = gaugeMin(model)
            val hi = gaugeMax(model)
            return ((pct - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        }

        /** Concrete BPM bounds for each zone, for the gauge and the settings table. */
        fun bands(profile: UserProfile): List<ZoneBand> {
            val model = profile.intensityModel
            return ordered.map { z ->
                ZoneBand(
                    zone = z,
                    loBpm = profile.hrForIntensity(z.low(model)),
                    hiBpm = profile.hrForIntensity(z.high(model)),
                    gaugeStart = gaugeFractionForPct(z.low(model), model),
                    gaugeEnd = gaugeFractionForPct(z.high(model), model),
                )
            }
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
 * a 70% fast target with a 20-point band means "anywhere from 50% to 70%",
 * e.g. 124-146 bpm rather than "hit 146 exactly".
 *
 * Which edge matters depends on the bout:
 *
 *  - **Fast**: both. Too low is under-training, too high is over-cooking it.
 *  - **Slow**: only the ceiling. The slow bout is recovery, so drifting below
 *    the band is a success, not a fault, and is never coached against.
 *
 * The percentages are in whatever scale [UserProfile.intensityModel] says, and
 * every conversion here goes through the profile, so a band can never be read
 * in the wrong scale.
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

    fun lowBpm(p: UserProfile): Int = p.hrForIntensity(lowPct)
    fun highBpm(p: UserProfile): Int = p.hrForIntensity(highPct)
    fun aimBpm(p: UserProfile): Int = p.hrForIntensity(aimPct)

    /** e.g. "124-146". */
    fun rangeText(p: UserProfile): String = "${lowBpm(p)}-${highBpm(p)}"

    fun contains(hr: Int, p: UserProfile): Boolean {
        val pct = p.intensityForHr(hr)
        return pct >= lowPct && pct <= highPct
    }

    fun verdict(hr: Int, p: UserProfile): TargetVerdict {
        val pct = p.intensityForHr(hr)
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
        else ((p.intensityForHr(hr) / highPct) * 100).roundToInt().coerceIn(0, 300)

    /**
     * Linear blend towards [other], used to ramp the expected heart rate across
     * a bout change instead of stepping it. [f] 0 keeps this band, 1 becomes
     * [other]. The flags come from the destination: it is the bout you are
     * heading into that decides which edges are being judged.
     */
    fun lerpTo(other: TargetBand, f: Double): TargetBand {
        val t = f.coerceIn(0.0, 1.0)
        if (t <= 0.0) return this
        if (t >= 1.0) return other
        return TargetBand(
            lowPct = lowPct + (other.lowPct - lowPct) * t,
            highPct = highPct + (other.highPct - highPct) * t,
            floorMatters = other.floorMatters,
            ceilingMatters = other.ceilingMatters,
            gracePct = other.gracePct,
        )
    }
}

enum class TargetVerdict { WAY_BELOW, BELOW, ON_TARGET, ABOVE, WAY_ABOVE }
