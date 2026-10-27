package com.telecomprofi.japanesewalk.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.core.IntensityModel
import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.MaxHrFormula
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.core.UserProfile
import com.telecomprofi.japanesewalk.data.prefs.AppSettings
import com.telecomprofi.japanesewalk.data.prefs.CoachSettings
import com.telecomprofi.japanesewalk.data.prefs.CoolDownSettings
import com.telecomprofi.japanesewalk.data.prefs.CycleSettings
import com.telecomprofi.japanesewalk.data.prefs.DisplaySettings
import com.telecomprofi.japanesewalk.data.prefs.HrmSettings
import com.telecomprofi.japanesewalk.data.prefs.ReminderSettings
import com.telecomprofi.japanesewalk.game.ReminderScheduler
import com.telecomprofi.japanesewalk.game.WeeklySummaryScheduler
import com.telecomprofi.japanesewalk.hrm.BleHeartRateMonitor
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.hrm.ScannedDevice
import com.telecomprofi.japanesewalk.trainer.TrainerControl
import com.telecomprofi.japanesewalk.trainer.TrainerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = Graph.container
    private val monitor = BleHeartRateMonitor(app)
    private val trainer = TrainerControl(app)

    val settings: StateFlow<AppSettings> = container.prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    val hrmState: StateFlow<HrmState> = monitor.state

    private var scanJob: Job? = null

    private val _trainerDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val trainerDevices: StateFlow<List<ScannedDevice>> = _trainerDevices.asStateFlow()

    val trainerState: StateFlow<TrainerState> = trainer.state
    val trainerResponse: StateFlow<String?> = trainer.lastResponse

    private var trainerScanJob: Job? = null

    fun updateProfile(transform: (UserProfile) -> UserProfile) = viewModelScope.launch {
        val current = container.prefs.settings.first().profile
        container.prefs.updateProfile(transform(current))
    }

    fun updateProtocol(transform: (IwtProtocol) -> IwtProtocol) = viewModelScope.launch {
        val current = container.prefs.settings.first().protocol
        container.prefs.updateProtocol(transform(current))
    }

    fun updateCoach(transform: (CoachSettings) -> CoachSettings) = viewModelScope.launch {
        val current = container.prefs.settings.first().coach
        container.prefs.updateCoach(transform(current))
    }

    fun updateCycle(transform: (CycleSettings) -> CycleSettings) = viewModelScope.launch {
        val current = container.prefs.settings.first().cycle
        container.prefs.updateCycle(transform(current))
    }

    fun setMode(mode: WorkoutMode) = viewModelScope.launch {
        container.prefs.setMode(mode)
    }

    /**
     * Trainers advertise the Fitness Machine Service, but a few only expose it
     * once connected, so the UI can fall back to an unfiltered scan just as it
     * does for straps.
     */
    fun startTrainerScan(filterByService: Boolean = true) {
        trainerScanJob?.cancel()
        _trainerDevices.value = emptyList()
        trainerScanJob = viewModelScope.launch {
            trainer.scan(filterByService).collect { _trainerDevices.value = it }
        }
    }

    fun stopTrainerScan() {
        trainerScanJob?.cancel()
        trainerScanJob = null
    }

    fun pairTrainer(device: ScannedDevice) {
        updateCycle { it.copy(deviceAddress = device.address, deviceName = device.name) }
        stopTrainerScan()
    }

    fun forgetTrainer() = updateCycle { it.copy(deviceAddress = null, deviceName = null) }

    fun updateCoolDown(transform: (CoolDownSettings) -> CoolDownSettings) = viewModelScope.launch {
        val current = container.prefs.settings.first().coolDown
        container.prefs.updateCoolDown(transform(current))
    }

    fun updateDisplay(transform: (DisplaySettings) -> DisplaySettings) = viewModelScope.launch {
        val current = container.prefs.settings.first().display
        container.prefs.updateDisplay(transform(current))
    }

    /**
     * Switch intensity model. The two are mutually exclusive by construction —
     * one enum field — but picking the watch-style model also drops a
     * hand-entered maximum, because the whole point of asking for "220 − age"
     * is to have the age formula decide where the zones sit.
     */
    fun setIntensityModel(model: IntensityModel, formula: MaxHrFormula? = null) = updateProfile {
        it.copy(
            intensityModel = model,
            maxHrFormula = formula ?: it.maxHrFormula,
            useMeasuredMaxHr = if (formula != null) false else it.useMeasuredMaxHr,
        )
    }

    fun updateHrm(transform: (HrmSettings) -> HrmSettings) = viewModelScope.launch {
        val current = container.prefs.settings.first().hrm
        container.prefs.updateHrm(transform(current))
    }

    fun updateReminders(transform: (ReminderSettings) -> ReminderSettings) = viewModelScope.launch {
        val current = container.prefs.settings.first().reminders
        val updated = transform(current)
        container.prefs.updateReminders(updated)
        ReminderScheduler.apply(getApplication(), updated)
        WeeklySummaryScheduler.apply(getApplication(), updated)
    }

    fun startScan(filterByService: Boolean = true) {
        scanJob?.cancel()
        _devices.value = emptyList()
        scanJob = viewModelScope.launch {
            monitor.scan(filterByService).collect { _devices.value = it }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * The heart-rate source is one or the other, never both: pairing a strap
     * turns the simulator off, and the Debug toggle turns it back on.
     */
    fun pair(device: ScannedDevice) {
        updateHrm { it.copy(deviceAddress = device.address, deviceName = device.name, simulate = false) }
        stopScan()
    }

    fun forgetStrap() = updateHrm { it.copy(deviceAddress = null, deviceName = null) }

    /** Treadmill mode. The same flag the Summary tab toggles, written the same way. */
    fun setIndoorMode(value: Boolean) = viewModelScope.launch {
        container.prefs.setIndoorMode(value)
    }

    override fun onCleared() {
        stopScan()
        stopTrainerScan()
        trainer.stop()
        monitor.stop()
        super.onCleared()
    }
}
