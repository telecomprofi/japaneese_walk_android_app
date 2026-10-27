package com.telecomprofi.japanesewalk.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.core.DataMetric
import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.TrainingStatus
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.data.prefs.DataSettings
import com.telecomprofi.japanesewalk.data.db.SessionEntity
import com.telecomprofi.japanesewalk.data.repo.SummaryStats
import com.telecomprofi.japanesewalk.data.repo.WeekTable
import com.telecomprofi.japanesewalk.workout.WorkoutHub
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

    val mode: StateFlow<WorkoutMode> = container.prefs.settings
        .map { it.mode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutMode.WALK)

    val trainerName: StateFlow<String?> = container.prefs.settings
        .map { it.cycle.deviceName ?: it.cycle.deviceAddress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val plannedMinutes: StateFlow<Int> = container.prefs.settings
        .map { IntervalPlan(it.protocol, it.profile.intensityModel).totalSec / 60 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    /** The session just finished — the card the Summary tab opens onto. */
    val lastSession: StateFlow<SessionEntity?> = container.db.sessionDao().latest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Monday to Sunday, day by day. */
    val weekTable: StateFlow<WeekTable> = container.statsRepository.weekTable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekTable.EMPTY)

    /** Fitness, fatigue, and whether today should be a rest day. */
    val training: StateFlow<TrainingStatus> = container.statsRepository.trainingStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainingStatus())

    val workout = WorkoutHub.state
    val outcome = WorkoutHub.lastOutcome

    fun dismissOutcome() = WorkoutHub.consumeOutcome()

    fun setIndoorMode(value: Boolean) {
        viewModelScope.launch { container.prefs.setIndoorMode(value) }
    }

    fun setMode(mode: WorkoutMode) {
        viewModelScope.launch {
            container.prefs.setMode(mode)
            // Swap the Data tiles too, but only if they are still the other
            // mode's stock set — a hand-picked selection is left alone. Without
            // this a rider stares at three blank GPS tiles and no power.
            val current = container.prefs.settings.first().data.metrics
            val next = when {
                mode == WorkoutMode.CYCLE && current == DataMetric.WALK_DEFAULT ->
                    DataMetric.CYCLE_DEFAULT
                mode == WorkoutMode.WALK && current == DataMetric.CYCLE_DEFAULT ->
                    DataMetric.WALK_DEFAULT
                else -> null
            }
            next?.let { container.prefs.updateData(DataSettings(it)) }
        }
    }
}
