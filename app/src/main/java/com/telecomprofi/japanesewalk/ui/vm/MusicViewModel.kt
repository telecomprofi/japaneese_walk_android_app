package com.telecomprofi.japanesewalk.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.data.db.CatalogueEntity
import com.telecomprofi.japanesewalk.data.db.TrackEntity
import com.telecomprofi.japanesewalk.data.prefs.MusicSettings
import com.telecomprofi.japanesewalk.music.AnalysisProgress
import com.telecomprofi.japanesewalk.music.CatalogueReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MusicViewModel : ViewModel() {

    private val container = Graph.container
    private val catalogueDao = container.db.catalogueDao()
    private val library = container.musicLibrary

    val music: StateFlow<MusicSettings> = container.prefs.settings
        .map { it.music }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicSettings())

    /** The workout list — what actually plays. */
    val tracks: StateFlow<List<TrackEntity>> = container.db.trackDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val catalogueCount: StateFlow<Int> = catalogueDao.count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Catalogue search results. Empty query lists the shelf. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<CatalogueEntity>> = _query
        .flatMapLatest { q -> catalogueDao.search(q.trim(), 200) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _report = MutableStateFlow<CatalogueReport?>(null)
    val report: StateFlow<CatalogueReport?> = _report.asStateFlow()

    private val _progress = MutableStateFlow<AnalysisProgress?>(null)
    val progress: StateFlow<AnalysisProgress?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Recomputed with the playlist: cheap, and always right when it is read. */
    val accessProblem: StateFlow<String?> = container.db.trackDao().all()
        .map { library.accessProblem(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private var job: Job? = null

    fun setQuery(value: String) { _query.value = value }

    fun update(transform: (MusicSettings) -> MusicSettings) {
        viewModelScope.launch {
            val current = container.prefs.settings.first().music
            container.prefs.updateMusic(transform(current))
        }
    }

    fun setFolder(treeUri: String) {
        viewModelScope.launch {
            val current = container.prefs.settings.first().music
            container.prefs.updateMusic(current.copy(musicTreeUri = treeUri))
            scanFolder()
        }
    }

    fun scanFolder() {
        job?.cancel()
        job = viewModelScope.launch {
            _busy.value = true
            _status.value = "Starting…"
            try {
                val settings = container.prefs.settings.first().music
                _report.value = container.musicLibrary.scanFolder(
                    treeUri = settings.musicTreeUri,
                    onProgress = { _status.value = it },
                )
            } finally {
                _status.value = null
                _busy.value = false
            }
        }
    }

    fun addFromCatalogue(uri: String) {
        job?.cancel()
        job = viewModelScope.launch {
            container.musicLibrary.addToWorkout(listOf(uri))
            runAnalysis()
        }
    }

    fun addAllResults() {
        job?.cancel()
        job = viewModelScope.launch {
            val uris = results.value.map { it.uri }
            _busy.value = true
            _status.value = "Adding ${uris.size} track(s)…"
            try {
                container.musicLibrary.addToWorkout(uris)
            } finally {
                _status.value = null
                _busy.value = false
            }
            runAnalysis()
        }
    }

    fun addFiles(uris: List<Uri>) {
        job?.cancel()
        job = viewModelScope.launch {
            _busy.value = true
            _status.value = "Adding ${uris.size} file(s)…"
            try {
                container.musicLibrary.addFilesToWorkout(uris)
            } finally {
                _status.value = null
                _busy.value = false
            }
            runAnalysis()
        }
    }

    /** A tempo typed in by hand; it outranks measurement and survives a re-measure. */
    fun setBpm(track: TrackEntity, bpm: Double) = viewModelScope.launch {
        container.db.trackDao().setManualBpm(track.uri, bpm)
    }

    /** Hand the track back to the measurer. */
    fun clearBpm(track: TrackEntity) = viewModelScope.launch {
        container.db.trackDao().setAnalysis(
            uri = track.uri,
            bpm = 0.0,
            conf = 0.0,
            lufs = track.loudnessLufs,
            at = 0L,
        )
    }

    fun remove(track: TrackEntity) {
        viewModelScope.launch { container.musicLibrary.removeFromWorkout(track.uri) }
    }

    fun clearWorkout() {
        viewModelScope.launch { container.musicLibrary.clearWorkout() }
    }

    fun analyse() = runAnalysisJob()

    /** Take tempi from the files' own tags, which needs no decoding. */
    fun readTags() {
        job?.cancel()
        job = viewModelScope.launch {
            _busy.value = true
            try {
                val dropped = container.musicLibrary.dropUnplayable()
                val upgraded = container.musicLibrary.adoptTags { _progress.value = it }
                _status.value = buildString {
                    append(
                        if (upgraded > 0) {
                            "$upgraded tracks took their tempo from the file's own tag"
                        } else {
                            "No usable BPM tags found in the queued files"
                        }
                    )
                    if (dropped > 0) {
                        append(" · $dropped in formats this phone cannot play were removed")
                    }
                }
            } finally {
                _busy.value = false
                _progress.value = null
            }
        }
    }

    fun reanalyseAll() {
        job?.cancel()
        job = viewModelScope.launch {
            _busy.value = true
            _status.value = "Re-measuring every track…"
            try {
                container.musicLibrary.reanalyseAll {
                    _progress.value = it
                    _status.value = "Re-measuring ${it.done} of ${it.total}"
                }
            } finally {
                _progress.value = null
                _status.value = null
                _busy.value = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _busy.value = false
        _progress.value = null
        _status.value = null
    }

    private fun runAnalysisJob() {
        job?.cancel()
        job = viewModelScope.launch { runAnalysis() }
    }

    /** Body of the tempo pass. Safe to call from an already-running job. */
    private suspend fun runAnalysis() {
        _busy.value = true
        _status.value = "Measuring tempo…"
        try {
            container.musicLibrary.analysePending {
                _progress.value = it
                _status.value = "Measuring tempo ${it.done} of ${it.total}"
            }
        } finally {
            _progress.value = null
            _status.value = null
            _busy.value = false
        }
    }
}
