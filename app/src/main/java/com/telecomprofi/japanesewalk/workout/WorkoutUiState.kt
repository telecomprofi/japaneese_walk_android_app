package com.telecomprofi.japanesewalk.workout

import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.TrainerControlMode
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.core.ZoneBand
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.Segment
import com.telecomprofi.japanesewalk.core.TargetVerdict
import com.telecomprofi.japanesewalk.hrm.ContactStatus
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.music.NowPlaying

/**
 * [COOLING] is the stretch after the last bout: the plan is done but the strap
 * is still on and the minutes still count. It is deliberately not FINISHED —
 * FINISHED is what sends the UI back to the Summary screen.
 */
enum class EngineStatus { IDLE, RUNNING, PAUSED, COOLING, FINISHED }

/**
 * The geometry of the running session, as the engine snapshotted it at start.
 *
 * The set-progress bar and the set line need this, and they must not read it
 * from live settings: changing Sets from 5 to 2 mid-walk would redraw the bar
 * against a plan the engine is not running.
 */
data class SessionShape(
    val sets: Int = 5,
    val slowSec: Int = 3 * 60,
    val fastSec: Int = 3 * 60,
    val warmupSec: Int = 0,
    val startWithSlow: Boolean = true,
)

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
    val segmentElapsedSec: Int = 0,
    val segmentTotalSec: Int = 0,
    val segmentProgress: Float = 0f,
    val sessionProgress: Float = 0f,
    val nextPhase: Phase? = null,
    /** Elapsed inside the plan itself, i.e. excluding any cool-down. */
    val planElapsedSec: Int = 0,
    /** Extra tracked seconds after the plan finished. */
    val coolDownSec: Int = 0,
    /** Paused by a phone call or another app, not by the user. */
    val interrupted: Boolean = false,
    /** The plan the engine is actually running, not what Settings says now. */
    val shape: SessionShape = SessionShape(),
    /**
     * Zone bounds from the profile the session started with. Empty when idle,
     * in which case the screens fall back to the live profile — that way
     * switching intensity model mid-walk cannot leave the gauge painting one
     * scale's bands under another scale's needle.
     */
    val zoneBands: List<ZoneBand> = emptyList(),

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
    val secondsInZone: List<Int> = List(HrZone.entries.size) { 0 },
    val adherencePercent: Int = 0,

    val hrmState: HrmState = HrmState.Idle,
    val contact: ContactStatus = ContactStatus.NOT_SUPPORTED,
    val strapStale: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    /** What the player is doing this second — position, volume, ducked or not. */
    val musicStatus: com.telecomprofi.japanesewalk.music.MusicStatus =
        com.telecomprofi.japanesewalk.music.MusicStatus(),
    val targetCadenceSpm: Int = 0,

    // --- trainer-derived, all zero/false without a smart trainer ---
    val mode: WorkoutMode = WorkoutMode.WALK,
    val powerW: Int = 0,
    val cadenceRpm: Int = 0,
    val avgPowerW: Int = 0,
    val workKj: Double = 0.0,
    /** What the app last asked the trainer for, watts or resistance level. */
    val trainerTarget: Int = 0,
    /** Resistance level the trainer reports, when it reports one. */
    val resistanceLevel: Int = 0,
    /** Which of the two the target is expressed in. */
    val controlMode: TrainerControlMode = TrainerControlMode.POWER,
    val trainerConnected: Boolean = false,
    /** True once the trainer has accepted control, i.e. targets are being obeyed. */
    val trainerControlling: Boolean = false,

    // --- location-derived, all zero when there is no GPS ---
    val distanceMeters: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val gpsTracking: Boolean = false,
    val gpsFixes: Int = 0,
) {
    val isActive: Boolean
        get() = status == EngineStatus.RUNNING ||
            status == EngineStatus.PAUSED ||
            status == EngineStatus.COOLING

    val isCooling: Boolean get() = status == EngineStatus.COOLING

    val remainingSec: Int get() = (totalSec - planElapsedSec).coerceAtLeast(0)
}
