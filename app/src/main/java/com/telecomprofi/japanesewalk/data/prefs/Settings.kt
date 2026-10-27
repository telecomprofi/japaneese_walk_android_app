package com.telecomprofi.japanesewalk.data.prefs

import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.TrainerControlMode
import com.telecomprofi.japanesewalk.core.UserProfile
import com.telecomprofi.japanesewalk.core.WorkoutMode

data class MusicSettings(
    /** Persisted SAF tree URI of the Music folder (usually on the SD card). */
    val musicTreeUri: String? = null,
    val enabled: Boolean = true,
    /**
     * Let another app provide the music. The built-in player is not created at
     * all; the voice coach ducks whatever is playing instead of the app's own
     * playlist. Pace matching is unavailable in this mode, by definition.
     */
    val useExternalPlayer: Boolean = false,
    /** Time-stretch tracks to lock onto the target cadence. */
    val matchBpm: Boolean = true,
    /** Hard limit on time-stretch so music does not sound silly. */
    val maxStretchPercent: Int = 8,
    /** Allow half/double-time matches (a 65 BPM track fits a 130 spm cadence). */
    val allowOctaveMatch: Boolean = true,
    val volumePercent: Int = 90,
    /** Duck music to this share of its volume while the coach speaks. */
    val duckPercent: Int = 12,
    /**
     * Level every track to the same perceived loudness. Measured once per track
     * alongside the tempo; applied as an attenuation at playback, because the
     * player's volume tops out at 1.0 and there is no headroom to boost into.
     */
    val normaliseLoudness: Boolean = true,
    /**
     * How far a loud track may be turned down, and a quiet one lifted, to sit
     * with the rest of the playlist. The reference is the playlist's median
     * loudness, so the typical track is not touched at all — an earlier version
     * levelled everything to the *quietest* track and made the app half as loud
     * as every other player on the phone.
     */
    val maxCutDb: Int = 8,
    val maxBoostDb: Int = 6,
)

data class HrmSettings(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val autoConnect: Boolean = true,
    /** Feed synthetic heart rate so the app can be tried without a strap. */
    val simulate: Boolean = false,
)

data class CoachSettings(
    val voiceEnabled: Boolean = true,
    val announceZoneDrift: Boolean = true,
    val countdownSeconds: Int = 10,
    val halfwayCue: Boolean = true,
    val speechRate: Float = 1.0f,
    /** Minimum quiet time between any two non-urgent spoken cues. */
    val minCueGapSec: Int = 40,
    /** How long the heart rate must sit outside the band before it is mentioned. */
    val driftGraceSec: Int = 35,
    /** Never coach heart rate in the opening seconds of a bout; it needs time to respond. */
    val boutSettleSec: Int = 45,
)

/** Which tiles the Data screen shows, in reading order. */
data class DataSettings(
    val metrics: List<com.telecomprofi.japanesewalk.core.DataMetric> =
        com.telecomprofi.japanesewalk.core.DataMetric.WALK_DEFAULT,
)

/**
 * What happens once the last bout ends.
 *
 * People keep walking after the final interval, and the old behaviour dropped
 * the strap the instant the plan hit zero — throwing away real, easy-paced
 * activity. Tracking continues until the heart rate has genuinely settled.
 */
data class CoolDownSettings(
    val trackAfterFinish: Boolean = true,
    /** Hard stop, so a forgotten strap cannot log an hour of sofa. */
    val maxSec: Int = 10 * 60,
    /** Heart rate at or below this zone counts as settled. */
    val settledZoneIdx: Int = 1,
    /** How long it must stay settled before the session closes itself. */
    val settledSec: Int = 60,
    /**
     * Keep the session open for as long as you are still clearly working, not
     * merely until the heart rate has dipped into zone 1.
     *
     * People carry on walking after the last bout — home from the park, round
     * the block once more — and that is real activity at a real heart rate.
     * While the rate stays above [activeHrFloor] the strap, the music and the
     * minutes all keep running, and it is all recorded as one session.
     */
    val extendWhileActive: Boolean = true,
    /** Above this, you are still walking rather than standing about. */
    val activeHrFloor: Int = 100,
    /** The hard ceiling on an extended cool-down: 30 minutes plus 10. */
    val maxExtraSec: Int = 40 * 60,
)

data class DisplaySettings(
    /**
     * Hold the screen awake for the duration of a walk. Only ever applied while
     * a session is actually running, so it cannot flatten the battery in a
     * pocket between walks.
     */
    val keepScreenOn: Boolean = true,
)

/**
 * Indoor cycling on a smart trainer.
 *
 * Note what is *not* here: the heart-rate targets. The bouts are still judged by
 * heart rate exactly as they are on foot — the watts below are only the lever
 * the app pulls to get you there, which is why [autoAdjust] exists. Two people
 * on the same trainer need different watts for the same heart rate, and so does
 * the same person on a hot day.
 */
data class CycleSettings(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val controlMode: TrainerControlMode = TrainerControlMode.POWER,
    val easyWatts: Int = 60,
    val hardWatts: Int = 130,
    /** Used instead of watts when [controlMode] is RESISTANCE, 0-100. */
    val easyResistance: Int = 20,
    val hardResistance: Int = 55,
    val easyCadenceRpm: Int = 75,
    val hardCadenceRpm: Int = 92,
    /**
     * Nudge the trainer's target so the heart rate lands inside the prescribed
     * band. Small steps, well spaced, and only once the bout has settled — a
     * fast loop would chase the lag in heart rate and oscillate.
     */
    val autoAdjust: Boolean = true,
    val adjustStepWatts: Int = 5,
    val adjustEverySec: Int = 20,
    val minWatts: Int = 40,
    val maxWatts: Int = 250,
)

data class ReminderSettings(
    val enabled: Boolean = true,
    /** Local hour of day for the daily reminder. */
    val hourOfDay: Int = 10,
    /** Minute past the hour, in steps of five. */
    val minuteOfHour: Int = 0,
    val weeklyTargetMinutes: Int = 150,
    /** The Sunday-morning look back at the week just finished. */
    val weeklySummary: Boolean = true,
    val summaryHourOfDay: Int = 11,
)

data class AppSettings(
    val profile: UserProfile = UserProfile(),
    val protocol: IwtProtocol = IwtProtocol(),
    val music: MusicSettings = MusicSettings(),
    val hrm: HrmSettings = HrmSettings(),
    val coach: CoachSettings = CoachSettings(),
    val coolDown: CoolDownSettings = CoolDownSettings(),
    val mode: WorkoutMode = WorkoutMode.WALK,
    val cycle: CycleSettings = CycleSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val reminders: ReminderSettings = ReminderSettings(),
    val data: DataSettings = DataSettings(),
    /**
     * Treadmill mode: no GPS at all. Distance, speed and pace are meaningless on
     * a treadmill, and asking the radio for fixes indoors only costs battery.
     */
    val indoorMode: Boolean = false,
    val onboarded: Boolean = false,
)
