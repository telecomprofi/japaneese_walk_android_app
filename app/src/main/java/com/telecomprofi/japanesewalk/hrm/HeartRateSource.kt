package com.telecomprofi.japanesewalk.hrm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class ContactStatus { NOT_SUPPORTED, NO_CONTACT, CONTACT }

data class HrSample(
    val hr: Int,
    val timestampMs: Long,
    val contact: ContactStatus = ContactStatus.NOT_SUPPORTED,
    val energyKJ: Int? = null,
    val rrIntervalsMs: List<Int> = emptyList(),
) {
    /** Root mean square of successive RR differences, when the strap reports RR. */
    val rmssd: Double?
        get() {
            if (rrIntervalsMs.size < 2) return null
            var sum = 0.0
            for (i in 1 until rrIntervalsMs.size) {
                val d = (rrIntervalsMs[i] - rrIntervalsMs[i - 1]).toDouble()
                sum += d * d
            }
            return kotlin.math.sqrt(sum / (rrIntervalsMs.size - 1))
        }
}

sealed interface HrmState {
    data object Idle : HrmState
    data object Scanning : HrmState
    data class Connecting(val name: String?) : HrmState
    data class Connected(val name: String?, val batteryPercent: Int? = null) : HrmState
    data class Disconnected(val reason: String? = null) : HrmState
    data class Failed(val message: String) : HrmState
    data object Simulated : HrmState

    val isLive: Boolean get() = this is Connected || this is Simulated
}

data class ScannedDevice(val address: String, val name: String?, val rssi: Int)

/** Anything that can produce heart-rate samples: a BLE strap, or the simulator. */
interface HeartRateSource {
    val state: StateFlow<HrmState>
    val samples: Flow<HrSample>
    fun start()
    fun stop()
}
