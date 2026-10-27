package com.telecomprofi.japanesewalk.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.ZoneBand
import com.telecomprofi.japanesewalk.data.prefs.AppSettings
import com.telecomprofi.japanesewalk.data.repo.WeekStats
import com.telecomprofi.japanesewalk.workout.WorkoutHub
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class WalkViewModel : ViewModel() {

    private val container = Graph.container

    val settings: StateFlow<AppSettings> = container.prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val bands: StateFlow<List<ZoneBand>> = container.prefs.settings
        .map { HrZone.bands(it.profile) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HrZone.bands(AppSettings().profile))

    val week: StateFlow<WeekStats> = container.statsRepository.weekStats
        .stateIn(viewModelScope, SharingStarted.Eagerly, WeekStats.EMPTY)

    val workout = WorkoutHub.state
    val outcome = WorkoutHub.lastOutcome
    val info = WorkoutHub.scanInfo
    val musicMuted = WorkoutHub.musicMuted
    val externalMusic = WorkoutHub.externalMusic

    val plannedMinutes: StateFlow<Int> = container.prefs.settings
        .map { IntervalPlan(it.protocol, it.profile.intensityModel).totalSec / 60 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    fun dismissOutcome() = WorkoutHub.consumeOutcome()
    fun dismissInfo() = WorkoutHub.setInfo(null)
}
