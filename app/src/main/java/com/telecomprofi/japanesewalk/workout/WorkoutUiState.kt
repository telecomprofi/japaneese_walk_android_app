package com.telecomprofi.japanesewalk.workout

import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.Segment
import com.telecomprofi.japanesewalk.core.TargetVerdict
import com.telecomprofi.japanesewalk.hrm.ContactStatus
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.music.NowPlaying

enum class EngineStatus { IDLE, RUNNING, PAUSED, FINISHED }

/** Everything the walk screen renders, recomputed once a second. */
data class WorkoutUiState(
    val status: EngineStatus = EngineStatus.IDLE,
    val elapsedSec: Int = 0,
    val totalSec: Int = 1800,
    val segment: Segment? = null,
    val phase: Phase? = null,
    val setNumber: Int = 0,
    val totalSets: Int = 5,
    val segmentRemainingSec: Int = 0,
    val segmentProgress: Float = 0f,
    val sessionProgress: Float = 0f,
    val nextPhase: Phase? = null,

    val hr: Int? = null,
    val zone: HrZone? = null,
    val gaugeFraction: Float = 0f,
    val targetAimBpm: Int = 0,
    val targetLowBpm: Int = 0,
    val targetHighBpm: Int = 0,
    val targetLowFraction: Float = 0f,
    val targetHighFraction: Float = 0f,
    val verdict: TargetVerdict? = null,
    val percentOfTarget: Int = 0,

    val kcal: Int = 0,
    val avgHr: Int = 0,
    val maxHr: Int = 0,
    val secondsInZone: List<Int> = List(5) { 0 },
    val adherencePercent: Int = 0,

    val hrmState: HrmState = HrmState.Idle,
    val contact: ContactStatus = ContactStatus.NOT_SUPPORTED,
    val strapStale: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val targetCadenceSpm: Int = 0,

    // --- location-derived, all zero when there is no GPS ---
    val distanceMeters: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val gpsTracking: Boolean = false,
    val gpsFixes: Int = 0,
) {
    val isActive: Boolean get() = status == EngineStatus.RUNNING || status == EngineStatus.PAUSED
    val remainingSec: Int get() = (totalSec - elapsedSec).coerceAtLeast(0)
}
