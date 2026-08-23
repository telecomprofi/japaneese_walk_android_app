package com.telecomprofi.japanesewalk.core

import kotlin.math.roundToInt

/**
 * Pure, testable accumulator for one workout. The engine feeds it one tick at a
 * time; it owns every derived number the UI and the database need.
 */
class WorkoutAccumulator(private val profile: UserProfile) {

    var elapsedSec: Int = 0; private set
    var kcal: Double = 0.0; private set
    var maxHr: Int = 0; private set
    var samples: Int = 0; private set
    private var hrSum: Long = 0

    /** Seconds spent in each zone, indexed by [HrZone.idx] - 1. */
    val secondsInZone = IntArray(HrZone.entries.size)

    /** Seconds inside the target band of the active segment, and outside it. */
    var secondsOnTarget: Int = 0; private set
    var secondsOffTarget: Int = 0; private set

    /** Consecutive seconds the current verdict has held; drives voice coaching. */
    var streakVerdict: TargetVerdict = TargetVerdict.ON_TARGET; private set
    var streakSec: Int = 0; private set

    val avgHr: Int get() = if (samples == 0) 0 else (hrSum / samples).toInt()

    /**
     * Advance the workout by [dtSec] seconds.
     *
     * @param hr latest heart rate, or null when the strap has no reading.
     */
    fun tick(dtSec: Int, hr: Int?, segment: Segment?, band: TargetBand?) {
        if (dtSec <= 0) return
        elapsedSec += dtSec

        val phase = segment?.phase ?: Phase.SLOW
        kcal += Calories.kcalForInterval(hr, phase, dtSec.toDouble(), profile)

        if (hr != null && hr > 30) {
            samples += dtSec
            hrSum += hr.toLong() * dtSec
            if (hr > maxHr) maxHr = hr
            val zone = HrZone.forHr(hr, profile)
            secondsInZone[zone.idx - 1] += dtSec

            if (band != null) {
                val verdict = band.verdict(hr, profile)
                if (verdict == TargetVerdict.ON_TARGET) secondsOnTarget += dtSec else secondsOffTarget += dtSec
                if (verdict == streakVerdict) streakSec += dtSec else { streakVerdict = verdict; streakSec = dtSec }
            }
        }
    }

    fun resetStreak() { streakSec = 0 }

    /**
     * Jump the clock forward without crediting heart-rate time or calories.
     * Used when the user skips an interval, so skipped minutes never inflate
     * the session totals.
     */
    fun skipAhead(seconds: Int) {
        if (seconds > 0) elapsedSec += seconds
    }

    /** Share of the session spent inside the prescribed band, 0..100. */
    val adherencePercent: Int
        get() {
            val total = secondsOnTarget + secondsOffTarget
            return if (total == 0) 0 else ((secondsOnTarget * 100.0) / total).roundToInt()
        }

    fun kcalRounded(): Int = kcal.roundToInt()
}
