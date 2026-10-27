package com.telecomprofi.japanesewalk.core

import kotlin.math.roundToInt

/**
 * How training intensity is expressed. The two are **mutually exclusive**: one
 * is chosen in Settings and everything downstream — zone colours, interval
 * targets, the gauge scale, the voice coach — reads the same one.
 *
 * They are genuinely different scales, not two views of one number:
 *
 *  - [HR_RESERVE] (Karvonen) measures effort between resting and maximum, so it
 *    adapts to fitness: `hr = rest + pct x (max - rest)`. It is what the
 *    published IWT protocol (Nemoto et al.) is defined in.
 *  - [MAX_HR] measures a plain share of maximum: `hr = pct x max`. It is what
 *    Samsung Health, Garmin and most watches show, which is why the boundaries
 *    feel familiar if that is what you are used to.
 */
enum class IntensityModel(val label: String, val blurb: String) {
    HR_RESERVE(
        "% of heart-rate reserve",
        "Karvonen. Counts effort from your resting rate upwards, so it follows your fitness. " +
            "What the published interval-walking protocol is written in.",
    ),
    MAX_HR(
        "% of max heart rate",
        "What watches show. Zone 3 starts at 70% of maximum, so the boundaries match a " +
            "Galaxy Watch or Garmin rather than the research protocol.",
    ),
}

/** Which age formula predicts maximum heart rate when it has not been measured. */
enum class MaxHrFormula(val label: String, val blurb: String) {
    TANAKA("208 − 0.7 × age", "Tanaka et al. Fits large populations better than 220 − age, especially over 40."),
    CLASSIC_220("220 − age", "The old classroom formula. Lower than Tanaka past 40, and what most watches assume."),
}

/**
 * Everything the app needs to know about the (single, local) user in order to
 * turn a raw BPM reading into a training intensity.
 *
 * [hrForIntensity] and [intensityForHr] are the only conversions the rest of the
 * app should use: they honour [intensityModel], so switching model switches the
 * whole app at once and nothing can end up half in one scale and half in another.
 */
data class UserProfile(
    val ageYears: Int = 40,
    val restingHr: Int = 60,
    /** Measured/known HRmax. Only used when [useMeasuredMaxHr] is true. */
    val measuredMaxHr: Int = 180,
    val useMeasuredMaxHr: Boolean = false,
    val weightKg: Double = 75.0,
    val isMale: Boolean = true,
    val intensityModel: IntensityModel = IntensityModel.HR_RESERVE,
    val maxHrFormula: MaxHrFormula = MaxHrFormula.TANAKA,
) {
    val predictedMaxHr: Int
        get() = when (maxHrFormula) {
            MaxHrFormula.TANAKA -> (208.0 - 0.7 * ageYears).roundToInt()
            MaxHrFormula.CLASSIC_220 -> 220 - ageYears
        }

    val maxHr: Int
        get() = if (useMeasuredMaxHr && measuredMaxHr > restingHr + 20) measuredMaxHr else predictedMaxHr

    /** Heart rate reserve, always >= 1 so it is safe as a divisor. */
    val hrReserve: Int get() = (maxHr - restingHr).coerceAtLeast(1)

    // --- Karvonen ---------------------------------------------------------

    /** Karvonen target heart rate for a fraction of heart rate reserve. */
    fun hrForReservePct(pct: Double): Int = (restingHr + pct * hrReserve).roundToInt()

    /** Inverse of [hrForReservePct]; may return values <0 or >1. */
    fun reservePctForHr(hr: Int): Double = (hr - restingHr).toDouble() / hrReserve

    // --- plain %HRmax -----------------------------------------------------

    fun hrForMaxPct(pct: Double): Int = (pct * maxHr).roundToInt()

    fun maxPctForHr(hr: Int): Double = hr.toDouble() / maxHr.coerceAtLeast(1)

    // --- model-aware, and what the app actually calls ---------------------

    fun hrForIntensity(pct: Double): Int = when (intensityModel) {
        IntensityModel.HR_RESERVE -> hrForReservePct(pct)
        IntensityModel.MAX_HR -> hrForMaxPct(pct)
    }

    fun intensityForHr(hr: Int): Double = when (intensityModel) {
        IntensityModel.HR_RESERVE -> reservePctForHr(hr)
        IntensityModel.MAX_HR -> maxPctForHr(hr)
    }

    fun isPlausible(): Boolean =
        ageYears in 10..100 &&
            restingHr in 30..110 &&
            weightKg in 25.0..300.0 &&
            maxHr > restingHr + 20
}
