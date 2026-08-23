package com.telecomprofi.japanesewalk.hrm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Fake strap for trying the app indoors, without hardware. Heart rate drifts
 * towards whatever the interval engine is currently asking for, with a lag and
 * a little noise so the rings and the voice coaching behave realistically.
 */
class SimulatedHeartRateSource(
    private val scope: CoroutineScope,
    private val restingHr: Int = 60,
    /** Where the workout wants the heart rate right now, in BPM. */
    private val targetProvider: () -> Int?,
) : HeartRateSource {

    private val _state = MutableStateFlow<HrmState>(HrmState.Idle)
    override val state: StateFlow<HrmState> = _state.asStateFlow()

    private val _samples = MutableSharedFlow<HrSample>(replay = 1, extraBufferCapacity = 16)
    override val samples: Flow<HrSample> = _samples.asSharedFlow()

    private var job: Job? = null
    private var current = restingHr.toDouble()
    private val rnd = Random(System.nanoTime())

    override fun start() {
        if (job?.isActive == true) return
        current = restingHr + 8.0
        _state.value = HrmState.Simulated
        job = scope.launch {
            while (isActive) {
                val target = (targetProvider() ?: (restingHr + 15)).toDouble()
                // First-order lag: hearts do not jump, they ramp.
                val step = (target - current) * 0.12
                current += step + (rnd.nextDouble() - 0.5) * 2.4
                current = current.coerceIn(40.0, 210.0)
                _samples.tryEmit(
                    HrSample(
                        hr = current.roundToInt(),
                        timestampMs = System.currentTimeMillis(),
                        contact = ContactStatus.CONTACT,
                    )
                )
                delay(1000)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _state.value = HrmState.Idle
    }
}
