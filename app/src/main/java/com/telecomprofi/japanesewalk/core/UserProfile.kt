package com.telecomprofi.japanesewalk.core

import kotlin.math.roundToInt

/**
 * Everything the app needs to know about the (single, local) user in order to
 * turn a raw BPM reading into a training intensity.
 *
 * Intensity is expressed as a fraction of Heart Rate Reserve (Karvonen):
 *
 *     targetHr = restingHr + pct * (maxHr - restingHr)
 *
 * The Japanese Walking / Interval Walking Training protocol (Nemoto et al.)
 * alternates ~70% ("fast") and ~40% ("slow") intensity, which is why HRR is
 * used rather than plain %HRmax: 40% of HRmax is below most people's resting
 * heart rate and therefore useless as a floor.
 */
data class UserProfile(
    val ageYears: Int = 40,
    val restingHr: Int = 60,
    /** Measured/known HRmax. Only used when [useMeasuredMaxHr] is true. */
    val measuredMaxHr: Int = 180,
    val useMeasuredMaxHr: Boolean = false,
    val weightKg: Double = 75.0,
    val isMale: Boolean = true,
) {
    /** Tanaka et al. is a better age predictor than 220-age; both offered. */
    val predictedMaxHr: Int get() = (208.0 - 0.7 * ageYears).roundToInt()

    val maxHr: Int
        get() = if (useMeasuredMaxHr && measuredMaxHr > restingHr + 20) measuredMaxHr else predictedMaxHr

    /** Heart rate reserve, always >= 1 so it is safe as a divisor. */
    val hrReserve: Int get() = (maxHr - restingHr).coerceAtLeast(1)

    /** Karvonen target heart rate for a fraction of heart rate reserve. */
    fun hrForReservePct(pct: Double): Int = (restingHr + pct * hrReserve).roundToInt()

    /** Inverse of [hrForReservePct]; may return values <0 or >1. */
    fun reservePctForHr(hr: Int): Double = (hr - restingHr).toDouble() / hrReserve

    fun isPlausible(): Boolean =
        ageYears in 10..100 &&
            restingHr in 30..110 &&
            weightKg in 25.0..300.0 &&
            maxHr > restingHr + 20
}
