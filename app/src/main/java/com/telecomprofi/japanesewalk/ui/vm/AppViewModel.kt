package com.telecomprofi.japanesewalk.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.data.prefs.AppSettings
import com.telecomprofi.japanesewalk.workout.WorkoutHub
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The little bit of state that belongs to the whole app rather than to one tab.
 *
 * The keep-awake flag lives here on purpose: hanging it off the Walk screen
 * looked right but the pager disposes that page once you swipe two tabs away,
 * which dropped the flag and let the screen sleep in the middle of a walk.
 */
class AppViewModel : ViewModel() {

    private val container = Graph.container

    val keepScreenOn: StateFlow<Boolean> = container.prefs.settings
        .map { it.display.keepScreenOn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings().display.keepScreenOn)

    /** Drives the second tab's icon, so the bar shows which kind of session is armed. */
    val mode: StateFlow<WorkoutMode> = container.prefs.settings
        .map { it.mode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WorkoutMode.WALK)

    val workout = WorkoutHub.state
}
