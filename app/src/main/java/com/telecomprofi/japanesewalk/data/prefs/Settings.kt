package com.telecomprofi.japanesewalk.data.prefs

import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.UserProfile

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
        com.telecomprofi.japanesewalk.core.DataMetric.DEFAULT,
)

data class ReminderSettings(
    val enabled: Boolean = true,
    /** Local hour of day for the daily reminder. */
    val hourOfDay: Int = 10,
    /** Minute past the hour, in steps of five. */
    val minuteOfHour: Int = 0,
    val weeklyTargetMinutes: Int = 150,
)

data class AppSettings(
    val profile: UserProfile = UserProfile(),
    val protocol: IwtProtocol = IwtProtocol(),
    val music: MusicSettings = MusicSettings(),
    val hrm: HrmSettings = HrmSettings(),
    val coach: CoachSettings = CoachSettings(),
    val reminders: ReminderSettings = ReminderSettings(),
    val data: DataSettings = DataSettings(),
    /**
     * Treadmill mode: no GPS at all. Distance, speed and pace are meaningless on
     * a treadmill, and asking the radio for fixes indoors only costs battery.
     */
    val indoorMode: Boolean = false,
    val onboarded: Boolean = false,
)
