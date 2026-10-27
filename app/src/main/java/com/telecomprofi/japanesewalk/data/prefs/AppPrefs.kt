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
import com.telecomprofi.japanesewalk.core.IntensityModel
import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.MaxHrFormula
import com.telecomprofi.japanesewalk.core.TrainerControlMode
import com.telecomprofi.japanesewalk.core.UserProfile
import com.telecomprofi.japanesewalk.core.WorkoutMode
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
        val intensityModel = stringPreferencesKey("intensity_model")
        val maxHrFormula = stringPreferencesKey("max_hr_formula")

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
        val slowMaxPct = doublePreferencesKey("slow_max_pct")
        val fastMaxPct = doublePreferencesKey("fast_max_pct")
        val bandWidthMaxPct = doublePreferencesKey("band_width_max_pct")
        val transitionSec = intPreferencesKey("transition_sec")

        val musicTree = stringPreferencesKey("music_tree_uri")
        val musicEnabled = booleanPreferencesKey("music_enabled")
        val externalPlayer = booleanPreferencesKey("use_external_player")
        val matchBpm = booleanPreferencesKey("match_bpm")
        val maxStretch = intPreferencesKey("max_stretch_pct")
        val octaveMatch = booleanPreferencesKey("octave_match")
        val volume = intPreferencesKey("volume_pct")
        val duck = intPreferencesKey("duck_pct")
        val normaliseLoudness = booleanPreferencesKey("normalise_loudness")
        val maxCutDb = intPreferencesKey("level_max_cut_db")
        val maxBoostDb = intPreferencesKey("level_max_boost_db")

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
        val weeklySummary = booleanPreferencesKey("weekly_summary_on")
        val summaryHour = intPreferencesKey("weekly_summary_hour")
        val dataMetrics = stringPreferencesKey("data_metrics")

        val coolDownTrack = booleanPreferencesKey("cooldown_track")
        val coolDownMax = intPreferencesKey("cooldown_max_sec")
        val coolDownZone = intPreferencesKey("cooldown_settled_zone")
        val coolExtend = booleanPreferencesKey("cooldown_extend_active")
        val coolHrFloor = intPreferencesKey("cooldown_active_hr")
        val coolMaxExtra = intPreferencesKey("cooldown_max_extra_sec")
        val coolDownSettled = intPreferencesKey("cooldown_settled_sec")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")

        val workoutMode = stringPreferencesKey("workout_mode")
        val trainerAddress = stringPreferencesKey("trainer_address")
        val trainerName = stringPreferencesKey("trainer_name")
        val trainerControlMode = stringPreferencesKey("trainer_control_mode")
        val easyWatts = intPreferencesKey("easy_watts")
        val hardWatts = intPreferencesKey("hard_watts")
        val easyResistance = intPreferencesKey("easy_resistance")
        val hardResistance = intPreferencesKey("hard_resistance")
        val easyRpm = intPreferencesKey("easy_rpm")
        val hardRpm = intPreferencesKey("hard_rpm")
        val autoAdjust = booleanPreferencesKey("trainer_auto_adjust")
        val adjustStep = intPreferencesKey("trainer_adjust_step")
        val adjustEvery = intPreferencesKey("trainer_adjust_every")
        val minWatts = intPreferencesKey("trainer_min_watts")
        val maxWatts = intPreferencesKey("trainer_max_watts")

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
                intensityModel = this[K.intensityModel]?.let { name ->
                    IntensityModel.entries.firstOrNull { it.name == name }
                } ?: d.profile.intensityModel,
                maxHrFormula = this[K.maxHrFormula]?.let { name ->
                    MaxHrFormula.entries.firstOrNull { it.name == name }
                } ?: d.profile.maxHrFormula,
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
                slowMaxPct = this[K.slowMaxPct] ?: d.protocol.slowMaxPct,
                fastMaxPct = this[K.fastMaxPct] ?: d.protocol.fastMaxPct,
                bandWidthMaxPct = this[K.bandWidthMaxPct] ?: d.protocol.bandWidthMaxPct,
                transitionSec = this[K.transitionSec] ?: d.protocol.transitionSec,
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
                normaliseLoudness = this[K.normaliseLoudness] ?: d.music.normaliseLoudness,
                maxCutDb = this[K.maxCutDb] ?: d.music.maxCutDb,
                maxBoostDb = this[K.maxBoostDb] ?: d.music.maxBoostDb,
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
            coolDown = CoolDownSettings(
                trackAfterFinish = this[K.coolDownTrack] ?: d.coolDown.trackAfterFinish,
                maxSec = this[K.coolDownMax] ?: d.coolDown.maxSec,
                settledZoneIdx = this[K.coolDownZone] ?: d.coolDown.settledZoneIdx,
                settledSec = this[K.coolDownSettled] ?: d.coolDown.settledSec,
                extendWhileActive = this[K.coolExtend] ?: d.coolDown.extendWhileActive,
                activeHrFloor = this[K.coolHrFloor] ?: d.coolDown.activeHrFloor,
                maxExtraSec = this[K.coolMaxExtra] ?: d.coolDown.maxExtraSec,
            ),
            mode = this[K.workoutMode]?.let { name ->
                WorkoutMode.entries.firstOrNull { it.name == name }
            } ?: d.mode,
            cycle = CycleSettings(
                deviceAddress = this[K.trainerAddress],
                deviceName = this[K.trainerName],
                controlMode = this[K.trainerControlMode]?.let { name ->
                    TrainerControlMode.entries.firstOrNull { it.name == name }
                } ?: d.cycle.controlMode,
                easyWatts = this[K.easyWatts] ?: d.cycle.easyWatts,
                hardWatts = this[K.hardWatts] ?: d.cycle.hardWatts,
                easyResistance = this[K.easyResistance] ?: d.cycle.easyResistance,
                hardResistance = this[K.hardResistance] ?: d.cycle.hardResistance,
                easyCadenceRpm = this[K.easyRpm] ?: d.cycle.easyCadenceRpm,
                hardCadenceRpm = this[K.hardRpm] ?: d.cycle.hardCadenceRpm,
                autoAdjust = this[K.autoAdjust] ?: d.cycle.autoAdjust,
                adjustStepWatts = this[K.adjustStep] ?: d.cycle.adjustStepWatts,
                adjustEverySec = this[K.adjustEvery] ?: d.cycle.adjustEverySec,
                minWatts = this[K.minWatts] ?: d.cycle.minWatts,
                maxWatts = this[K.maxWatts] ?: d.cycle.maxWatts,
            ),
            display = DisplaySettings(
                keepScreenOn = this[K.keepScreenOn] ?: d.display.keepScreenOn,
            ),
            reminders = ReminderSettings(
                enabled = this[K.remindersOn] ?: d.reminders.enabled,
                hourOfDay = this[K.reminderHour] ?: d.reminders.hourOfDay,
                minuteOfHour = this[K.reminderMinute] ?: d.reminders.minuteOfHour,
                weeklyTargetMinutes = this[K.weeklyTarget] ?: d.reminders.weeklyTargetMinutes,
                weeklySummary = this[K.weeklySummary] ?: d.reminders.weeklySummary,
                summaryHourOfDay = this[K.summaryHour] ?: d.reminders.summaryHourOfDay,
            ),
            data = DataSettings(
                metrics = this[K.dataMetrics]?.let { DataMetric.parse(it) } ?: DataMetric.WALK_DEFAULT,
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
        it[K.intensityModel] = profile.intensityModel.name
        it[K.maxHrFormula] = profile.maxHrFormula.name
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
        it[K.slowMaxPct] = protocol.slowMaxPct
        it[K.fastMaxPct] = protocol.fastMaxPct
        it[K.bandWidthMaxPct] = protocol.bandWidthMaxPct
        it[K.transitionSec] = protocol.transitionSec
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
        it[K.normaliseLoudness] = music.normaliseLoudness
        it[K.maxCutDb] = music.maxCutDb
        it[K.maxBoostDb] = music.maxBoostDb
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

    suspend fun updateCoolDown(c: CoolDownSettings) = context.dataStore.edit {
        it[K.coolDownTrack] = c.trackAfterFinish
        it[K.coolDownMax] = c.maxSec
        it[K.coolDownZone] = c.settledZoneIdx
        it[K.coolDownSettled] = c.settledSec
        it[K.coolExtend] = c.extendWhileActive
        it[K.coolHrFloor] = c.activeHrFloor
        it[K.coolMaxExtra] = c.maxExtraSec
    }

    suspend fun setMode(mode: WorkoutMode) = context.dataStore.edit {
        it[K.workoutMode] = mode.name
    }

    suspend fun updateCycle(c: CycleSettings) = context.dataStore.edit {
        c.deviceAddress?.let { a -> it[K.trainerAddress] = a } ?: it.remove(K.trainerAddress)
        c.deviceName?.let { n -> it[K.trainerName] = n } ?: it.remove(K.trainerName)
        it[K.trainerControlMode] = c.controlMode.name
        it[K.easyWatts] = c.easyWatts
        it[K.hardWatts] = c.hardWatts
        it[K.easyResistance] = c.easyResistance
        it[K.hardResistance] = c.hardResistance
        it[K.easyRpm] = c.easyCadenceRpm
        it[K.hardRpm] = c.hardCadenceRpm
        it[K.autoAdjust] = c.autoAdjust
        it[K.adjustStep] = c.adjustStepWatts
        it[K.adjustEvery] = c.adjustEverySec
        it[K.minWatts] = c.minWatts
        it[K.maxWatts] = c.maxWatts
    }

    suspend fun updateDisplay(display: DisplaySettings) = context.dataStore.edit {
        it[K.keepScreenOn] = display.keepScreenOn
    }

    suspend fun updateReminders(r: ReminderSettings) = context.dataStore.edit {
        it[K.remindersOn] = r.enabled
        it[K.reminderHour] = r.hourOfDay
        it[K.reminderMinute] = r.minuteOfHour
        it[K.weeklyTarget] = r.weeklyTargetMinutes
        it[K.weeklySummary] = r.weeklySummary
        it[K.summaryHour] = r.summaryHourOfDay
    }

    suspend fun updateData(data: DataSettings) = context.dataStore.edit {
        it[K.dataMetrics] = DataMetric.serialise(data.metrics)
    }

    suspend fun setIndoorMode(value: Boolean) = context.dataStore.edit { it[K.indoorMode] = value }

    suspend fun setOnboarded(value: Boolean) = context.dataStore.edit { it[K.onboarded] = value }
}
