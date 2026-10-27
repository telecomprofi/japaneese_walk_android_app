package com.telecomprofi.japanesewalk.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.core.DataMetric
import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.UserProfile
import com.telecomprofi.japanesewalk.data.prefs.DataSettings
import com.telecomprofi.japanesewalk.workout.WorkoutHub
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DataViewModel : ViewModel() {

    private val container = Graph.container

    val workout = WorkoutHub.state

    /** Shared with the Walk screen, so the Mute button means the same on both. */
    val musicMuted = WorkoutHub.musicMuted

    val profile: StateFlow<UserProfile> = container.prefs.settings
        .map { it.profile }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile())

    /** Only used when nothing is running; live state wins during a session. */
    val plannedSets: StateFlow<Int> = container.prefs.settings
        .map { it.protocol.sets }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 5)

    val metrics: StateFlow<List<DataMetric>> = container.prefs.settings
        .map { it.data.metrics }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataMetric.DEFAULT)

    val bands = container.prefs.settings
        .map { HrZone.bands(it.profile) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HrZone.bands(UserProfile()))

    /** Toggle one tile on or off, preserving the canonical reading order. */
    fun toggle(metric: DataMetric) {
        viewModelScope.launch {
            val current = container.prefs.settings.first().data.metrics
            val next = if (metric in current) {
                current - metric
            } else {
                DataMetric.DEFAULT.filter { it in current || it == metric }
            }
            container.prefs.updateData(DataSettings(next))
        }
    }
}
