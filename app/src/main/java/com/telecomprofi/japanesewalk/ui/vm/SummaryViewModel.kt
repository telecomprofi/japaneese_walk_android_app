package com.telecomprofi.japanesewalk.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.data.repo.SummaryStats
import com.telecomprofi.japanesewalk.workout.WorkoutHub
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SummaryViewModel : ViewModel() {

    private val container = Graph.container

    val summary: StateFlow<SummaryStats> = container.statsRepository.summary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryStats.EMPTY)

    val indoorMode: StateFlow<Boolean> = container.prefs.settings
        .map { it.indoorMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val plannedMinutes: StateFlow<Int> = container.prefs.settings
        .map { IntervalPlan(it.protocol).totalSec / 60 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    val workout = WorkoutHub.state
    val outcome = WorkoutHub.lastOutcome

    fun dismissOutcome() = WorkoutHub.consumeOutcome()

    fun setIndoorMode(value: Boolean) {
        viewModelScope.launch { container.prefs.setIndoorMode(value) }
    }
}
