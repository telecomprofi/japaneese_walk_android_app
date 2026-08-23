package com.telecomprofi.japanesewalk.workout

import com.telecomprofi.japanesewalk.data.repo.SaveOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single place the UI reads live workout state from, and sends commands to.
 *
 * The engine lives inside the foreground service so a walk survives the screen
 * turning off; the hub is the thin, process-wide bridge to it.
 */
object WorkoutHub {

    private val _state = MutableStateFlow(WorkoutUiState())
    val state: StateFlow<WorkoutUiState> = _state.asStateFlow()

    private val _lastOutcome = MutableStateFlow<SaveOutcome?>(null)
    val lastOutcome: StateFlow<SaveOutcome?> = _lastOutcome.asStateFlow()

    private val _scanInfo = MutableStateFlow<String?>(null)
    val scanInfo: StateFlow<String?> = _scanInfo.asStateFlow()

    /** True while the app's own playlist is paused, e.g. to hand over to another player. */
    private val _musicMuted = MutableStateFlow(false)
    val musicMuted: StateFlow<Boolean> = _musicMuted.asStateFlow()

    /** True when this session has no player of its own at all. */
    private val _externalMusic = MutableStateFlow(false)
    val externalMusic: StateFlow<Boolean> = _externalMusic.asStateFlow()

    internal fun setMusicMuted(muted: Boolean) { _musicMuted.value = muted }

    internal fun setExternalMusic(external: Boolean) { _externalMusic.value = external }

    @Volatile
    private var engine: WorkoutEngine? = null

    val isActive: Boolean get() = engine?.isSessionActive == true

    internal fun attach(engine: WorkoutEngine) {
        this.engine = engine
        _lastOutcome.value = null
    }

    internal fun detach() {
        engine = null
    }

    internal fun publish(state: WorkoutUiState) {
        _state.value = state
    }

    internal fun publishOutcome(outcome: SaveOutcome) {
        _lastOutcome.value = outcome
    }

    internal fun currentTargetHr(): Int? = engine?.currentTargetHr()

    fun consumeOutcome() {
        _lastOutcome.value = null
    }

    fun setInfo(message: String?) {
        _scanInfo.value = message
    }

    fun togglePause() = engine?.togglePause()
    fun pause() = engine?.pause()
    fun resume() = engine?.resume()
    fun stop() = engine?.stop()
    fun skipSegment() = engine?.skipSegment()

    fun reset() {
        engine = null
        _state.value = WorkoutUiState()
        _musicMuted.value = false
    }
}
