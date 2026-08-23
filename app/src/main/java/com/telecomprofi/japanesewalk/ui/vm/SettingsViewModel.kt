package com.telecomprofi.japanesewalk.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.UserProfile
import com.telecomprofi.japanesewalk.data.prefs.AppSettings
import com.telecomprofi.japanesewalk.data.prefs.CoachSettings
import com.telecomprofi.japanesewalk.data.prefs.HrmSettings
import com.telecomprofi.japanesewalk.data.prefs.ReminderSettings
import com.telecomprofi.japanesewalk.game.ReminderScheduler
import com.telecomprofi.japanesewalk.hrm.BleHeartRateMonitor
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.hrm.ScannedDevice
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

    val settings: StateFlow<AppSettings> = container.prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    val hrmState: StateFlow<HrmState> = monitor.state

    private var scanJob: Job? = null

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

    fun updateHrm(transform: (HrmSettings) -> HrmSettings) = viewModelScope.launch {
        val current = container.prefs.settings.first().hrm
        container.prefs.updateHrm(transform(current))
    }

    fun updateReminders(transform: (ReminderSettings) -> ReminderSettings) = viewModelScope.launch {
        val current = container.prefs.settings.first().reminders
        val updated = transform(current)
        container.prefs.updateReminders(updated)
        ReminderScheduler.apply(getApplication(), updated)
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

    override fun onCleared() {
        stopScan()
        monitor.stop()
        super.onCleared()
    }
}
