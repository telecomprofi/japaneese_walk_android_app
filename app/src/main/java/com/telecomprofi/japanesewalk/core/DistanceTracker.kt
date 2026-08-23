package com.telecomprofi.japanesewalk.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance, which is all a walk ever needs. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }
}

/**
 * Turns a stream of GPS fixes into a distance.
 *
 * Raw fixes cannot be summed naively: standing still produces metres of drift
 * per minute, and an occasional fix lands hundreds of metres away. Three filters
 * deal with that — reject imprecise fixes, ignore movements below the noise
 * floor, and reject anything implying a speed no walker reaches.
 *
 * Pure Kotlin so the filtering can be tested without a satellite.
 */
class DistanceTracker(
    /** Fixes less precise than this are ignored outright. */
    private val maxAccuracyM: Float = 30f,
    /** Movements smaller than this are treated as drift, not progress. */
    private val minStepM: Double = 4.0,
    /** Faster than this is a bad fix, not a walk (8 m/s is a fast run). */
    private val maxSpeedMps: Double = 8.0,
) {
    var totalMeters: Double = 0.0
        private set

    var acceptedFixes: Int = 0
        private set

    var rejectedFixes: Int = 0
        private set

    /** Speed derived from the last accepted pair of fixes, in m/s. */
    var derivedSpeedMps: Double = 0.0
        private set

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTimeMs: Long = 0

    /**
     * Offer a fix. Returns the metres added, which is 0 when the fix was
     * filtered out.
     */
    fun add(lat: Double, lon: Double, accuracyM: Float, timeMs: Long): Double {
        if (accuracyM > maxAccuracyM || accuracyM < 0f) {
            rejectedFixes++
            return 0.0
        }
        val prevLat = lastLat
        val prevLon = lastLon
        if (prevLat == null || prevLon == null) {
            lastLat = lat
            lastLon = lon
            lastTimeMs = timeMs
            acceptedFixes++
            return 0.0
        }

        val step = Geo.distanceMeters(prevLat, prevLon, lat, lon)
        val dtSec = abs(timeMs - lastTimeMs) / 1000.0
        if (step < minStepM) {
            // Standing still. Keep the anchor so drift cannot accumulate.
            rejectedFixes++
            return 0.0
        }
        if (dtSec > 0 && step / dtSec > maxSpeedMps) {
            rejectedFixes++
            return 0.0
        }

        totalMeters += step
        derivedSpeedMps = if (dtSec > 0) step / dtSec else 0.0
        lastLat = lat
        lastLon = lon
        lastTimeMs = timeMs
        acceptedFixes++
        return step
    }

    val totalKm: Double get() = totalMeters / 1000.0

    fun reset() {
        totalMeters = 0.0
        derivedSpeedMps = 0.0
        acceptedFixes = 0
        rejectedFixes = 0
        lastLat = null
        lastLon = null
        lastTimeMs = 0
    }
}

/** Speed and pace conversions, and the strings shown on the Data screen. */
object Pace {

    fun kph(metersPerSecond: Double): Double = metersPerSecond * 3.6

    fun averageSpeedMps(meters: Double, seconds: Int): Double =
        if (seconds <= 0) 0.0 else meters / seconds

    /** Minutes per kilometre, or null when too slow to be meaningful. */
    fun minutesPerKm(metersPerSecond: Double): Double? {
        if (metersPerSecond <= 0.15) return null
        return 1000.0 / metersPerSecond / 60.0
    }

    /** "8:24" from minutes-per-km, or an em-dash placeholder. */
    fun formatPace(minutesPerKm: Double?): String {
        if (minutesPerKm == null || minutesPerKm.isNaN() || minutesPerKm > 99) return "--:--"
        val totalSeconds = (minutesPerKm * 60).roundToInt()
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    fun formatKm(meters: Double): String = "%.2f".format(meters / 1000.0)

    fun formatKph(metersPerSecond: Double): String = "%.1f".format(kph(metersPerSecond))

    fun kcalPerMinute(kcal: Int, seconds: Int): Double =
        if (seconds <= 0) 0.0 else kcal * 60.0 / seconds
}
