package com.telecomprofi.japanesewalk.workout

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "LocationTracker"

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    /** Platform-reported speed in m/s, or null when the fix carries none. */
    val speedMps: Float?,
    val timeMs: Long,
)

/**
 * GPS for distance, speed and pace.
 *
 * Uses the platform [LocationManager] rather than Play Services: walking needs
 * one fix a second from the GPS provider, and that avoids pulling a Google
 * dependency into an otherwise self-contained app.
 *
 * Location is entirely optional. If the permission is refused the tracker simply
 * never starts, and the distance-based tiles show a dash — everything else about
 * a walk works exactly as before.
 */
class LocationTracker(private val context: Context) {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _samples = MutableSharedFlow<LocationSample>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val samples: Flow<LocationSample> = _samples.asSharedFlow()

    var isTracking: Boolean = false
        private set

    private val listener = LocationListener { location -> _samples.tryEmit(location.toSample()) }

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    val isGpsEnabled: Boolean
        get() = try {
            manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (e: Exception) {
            false
        }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isTracking) return
        val locationManager = manager ?: return
        if (!hasPermission) {
            Log.i(TAG, "location permission not granted; distance tracking disabled")
            return
        }
        try {
            // One fix a second is plenty for walking and keeps the radio duty
            // cycle modest; the distance filter is applied downstream so that
            // standing still can be told apart from a missing fix.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            // Network fixes arrive sooner indoors and cover the first minute.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2_000L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            isTracking = true
        } catch (e: SecurityException) {
            Log.w(TAG, "location denied", e)
        } catch (e: Exception) {
            Log.w(TAG, "cannot start location updates", e)
        }
    }

    fun stop() {
        if (!isTracking) return
        isTracking = false
        try {
            manager?.removeUpdates(listener)
        } catch (e: Exception) {
            Log.w(TAG, "cannot stop location updates", e)
        }
    }

    private fun Location.toSample() = LocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyM = if (hasAccuracy()) accuracy else -1f,
        speedMps = if (hasSpeed()) speed else null,
        timeMs = if (time > 0) time else System.currentTimeMillis(),
    )
}
