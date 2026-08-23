package com.telecomprofi.japanesewalk.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.telecomprofi.japanesewalk.core.DataMetric
import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "japanese_walk_settings")

/**
 * All user settings, in one DataStore. Nothing here ever leaves the device.
 */
class AppPrefs(private val context: Context) {

    private object K {
        val age = intPreferencesKey("age")
        val restingHr = intPreferencesKey("resting_hr")
        val measuredMaxHr = intPreferencesKey("measured_max_hr")
        val useMeasuredMaxHr = booleanPreferencesKey("use_measured_max_hr")
        val weightKg = doublePreferencesKey("weight_kg")
        val isMale = booleanPreferencesKey("is_male")

        val sets = intPreferencesKey("sets")
        val slowSec = intPreferencesKey("slow_sec")
        val fastSec = intPreferencesKey("fast_sec")
        val warmupSec = intPreferencesKey("warmup_sec")
        val cooldownSec = intPreferencesKey("cooldown_sec")
        val slowPct = doublePreferencesKey("slow_pct")
        val fastPct = doublePreferencesKey("fast_pct")
        val bandWidthPct = doublePreferencesKey("band_width_pct")
        val slowCadence = intPreferencesKey("slow_cadence")
        val fastCadence = intPreferencesKey("fast_cadence")
        val startWithSlow = booleanPreferencesKey("start_with_slow")

        val musicTree = stringPreferencesKey("music_tree_uri")
        val musicEnabled = booleanPreferencesKey("music_enabled")
        val externalPlayer = booleanPreferencesKey("use_external_player")
        val matchBpm = booleanPreferencesKey("match_bpm")
        val maxStretch = intPreferencesKey("max_stretch_pct")
        val octaveMatch = booleanPreferencesKey("octave_match")
        val volume = intPreferencesKey("volume_pct")
        val duck = intPreferencesKey("duck_pct")

        val hrmAddress = stringPreferencesKey("hrm_address")
        val hrmName = stringPreferencesKey("hrm_name")
        val hrmAutoConnect = booleanPreferencesKey("hrm_auto_connect")
        val hrmSimulate = booleanPreferencesKey("hrm_simulate")

        val voiceEnabled = booleanPreferencesKey("voice_enabled")
        val announceDrift = booleanPreferencesKey("announce_drift")
        val countdownSec = intPreferencesKey("countdown_sec")
        val halfwayCue = booleanPreferencesKey("halfway_cue")
        val speechRate = floatPreferencesKey("speech_rate")
        val minCueGap = intPreferencesKey("min_cue_gap_sec")
        val driftGrace = intPreferencesKey("drift_grace_sec")
        val boutSettle = intPreferencesKey("bout_settle_sec")

        val remindersOn = booleanPreferencesKey("reminders_on")
        val reminderHour = intPreferencesKey("reminder_hour")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val weeklyTarget = intPreferencesKey("weekly_target_min")
        val dataMetrics = stringPreferencesKey("data_metrics")

        val onboarded = booleanPreferencesKey("onboarded")
        val indoorMode = booleanPreferencesKey("indoor_mode")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            profile = UserProfile(
                ageYears = this[K.age] ?: d.profile.ageYears,
                restingHr = this[K.restingHr] ?: d.profile.restingHr,
                measuredMaxHr = this[K.measuredMaxHr] ?: d.profile.measuredMaxHr,
                useMeasuredMaxHr = this[K.useMeasuredMaxHr] ?: d.profile.useMeasuredMaxHr,
                weightKg = this[K.weightKg] ?: d.profile.weightKg,
                isMale = this[K.isMale] ?: d.profile.isMale,
            ),
            protocol = IwtProtocol(
                sets = this[K.sets] ?: d.protocol.sets,
                slowSec = this[K.slowSec] ?: d.protocol.slowSec,
                fastSec = this[K.fastSec] ?: d.protocol.fastSec,
                warmupSec = this[K.warmupSec] ?: d.protocol.warmupSec,
                cooldownSec = this[K.cooldownSec] ?: d.protocol.cooldownSec,
                slowPct = this[K.slowPct] ?: d.protocol.slowPct,
                fastPct = this[K.fastPct] ?: d.protocol.fastPct,
                bandWidthPct = this[K.bandWidthPct] ?: d.protocol.bandWidthPct,
                slowCadenceSpm = this[K.slowCadence] ?: d.protocol.slowCadenceSpm,
                fastCadenceSpm = this[K.fastCadence] ?: d.protocol.fastCadenceSpm,
                startWithSlow = this[K.startWithSlow] ?: d.protocol.startWithSlow,
            ),
            music = MusicSettings(
                musicTreeUri = this[K.musicTree],
                enabled = this[K.musicEnabled] ?: d.music.enabled,
                useExternalPlayer = this[K.externalPlayer] ?: d.music.useExternalPlayer,
                matchBpm = this[K.matchBpm] ?: d.music.matchBpm,
                maxStretchPercent = this[K.maxStretch] ?: d.music.maxStretchPercent,
                allowOctaveMatch = this[K.octaveMatch] ?: d.music.allowOctaveMatch,
                volumePercent = this[K.volume] ?: d.music.volumePercent,
                duckPercent = this[K.duck] ?: d.music.duckPercent,
            ),
            hrm = HrmSettings(
                deviceAddress = this[K.hrmAddress],
                deviceName = this[K.hrmName],
                autoConnect = this[K.hrmAutoConnect] ?: d.hrm.autoConnect,
                simulate = this[K.hrmSimulate] ?: d.hrm.simulate,
            ),
            coach = CoachSettings(
                voiceEnabled = this[K.voiceEnabled] ?: d.coach.voiceEnabled,
                announceZoneDrift = this[K.announceDrift] ?: d.coach.announceZoneDrift,
                countdownSeconds = this[K.countdownSec] ?: d.coach.countdownSeconds,
                halfwayCue = this[K.halfwayCue] ?: d.coach.halfwayCue,
                speechRate = this[K.speechRate] ?: d.coach.speechRate,
                minCueGapSec = this[K.minCueGap] ?: d.coach.minCueGapSec,
                driftGraceSec = this[K.driftGrace] ?: d.coach.driftGraceSec,
                boutSettleSec = this[K.boutSettle] ?: d.coach.boutSettleSec,
            ),
            reminders = ReminderSettings(
                enabled = this[K.remindersOn] ?: d.reminders.enabled,
                hourOfDay = this[K.reminderHour] ?: d.reminders.hourOfDay,
                minuteOfHour = this[K.reminderMinute] ?: d.reminders.minuteOfHour,
                weeklyTargetMinutes = this[K.weeklyTarget] ?: d.reminders.weeklyTargetMinutes,
            ),
            data = DataSettings(
                metrics = this[K.dataMetrics]?.let { DataMetric.parse(it) } ?: DataMetric.DEFAULT,
            ),
            indoorMode = this[K.indoorMode] ?: false,
            onboarded = this[K.onboarded] ?: false,
        )
    }

    suspend fun updateProfile(profile: UserProfile) = context.dataStore.edit {
        it[K.age] = profile.ageYears
        it[K.restingHr] = profile.restingHr
        it[K.measuredMaxHr] = profile.measuredMaxHr
        it[K.useMeasuredMaxHr] = profile.useMeasuredMaxHr
        it[K.weightKg] = profile.weightKg
        it[K.isMale] = profile.isMale
    }

    suspend fun updateProtocol(protocol: IwtProtocol) = context.dataStore.edit {
        it[K.sets] = protocol.sets
        it[K.slowSec] = protocol.slowSec
        it[K.fastSec] = protocol.fastSec
        it[K.warmupSec] = protocol.warmupSec
        it[K.cooldownSec] = protocol.cooldownSec
        it[K.slowPct] = protocol.slowPct
        it[K.fastPct] = protocol.fastPct
        it[K.bandWidthPct] = protocol.bandWidthPct
        it[K.slowCadence] = protocol.slowCadenceSpm
        it[K.fastCadence] = protocol.fastCadenceSpm
        it[K.startWithSlow] = protocol.startWithSlow
    }

    suspend fun updateMusic(music: MusicSettings) = context.dataStore.edit {
        music.musicTreeUri?.let { uri -> it[K.musicTree] = uri } ?: it.remove(K.musicTree)
        it[K.musicEnabled] = music.enabled
        it[K.externalPlayer] = music.useExternalPlayer
        it[K.matchBpm] = music.matchBpm
        it[K.maxStretch] = music.maxStretchPercent
        it[K.octaveMatch] = music.allowOctaveMatch
        it[K.volume] = music.volumePercent
        it[K.duck] = music.duckPercent
    }

    suspend fun updateHrm(hrm: HrmSettings) = context.dataStore.edit {
        hrm.deviceAddress?.let { a -> it[K.hrmAddress] = a } ?: it.remove(K.hrmAddress)
        hrm.deviceName?.let { n -> it[K.hrmName] = n } ?: it.remove(K.hrmName)
        it[K.hrmAutoConnect] = hrm.autoConnect
        it[K.hrmSimulate] = hrm.simulate
    }

    suspend fun updateCoach(coach: CoachSettings) = context.dataStore.edit {
        it[K.voiceEnabled] = coach.voiceEnabled
        it[K.announceDrift] = coach.announceZoneDrift
        it[K.countdownSec] = coach.countdownSeconds
        it[K.halfwayCue] = coach.halfwayCue
        it[K.speechRate] = coach.speechRate
        it[K.minCueGap] = coach.minCueGapSec
        it[K.driftGrace] = coach.driftGraceSec
        it[K.boutSettle] = coach.boutSettleSec
    }

    suspend fun updateReminders(r: ReminderSettings) = context.dataStore.edit {
        it[K.remindersOn] = r.enabled
        it[K.reminderHour] = r.hourOfDay
        it[K.reminderMinute] = r.minuteOfHour
        it[K.weeklyTarget] = r.weeklyTargetMinutes
    }

    suspend fun updateData(data: DataSettings) = context.dataStore.edit {
        it[K.dataMetrics] = DataMetric.serialise(data.metrics)
    }

    suspend fun setIndoorMode(value: Boolean) = context.dataStore.edit { it[K.indoorMode] = value }

    suspend fun setOnboarded(value: Boolean) = context.dataStore.edit { it[K.onboarded] = value }
}
