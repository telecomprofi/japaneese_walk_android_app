package com.telecomprofi.japanesewalk.workout

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.data.prefs.AppSettings
import com.telecomprofi.japanesewalk.game.Notifications
import com.telecomprofi.japanesewalk.hrm.BleHeartRateMonitor
import com.telecomprofi.japanesewalk.hrm.HeartRateSource
import com.telecomprofi.japanesewalk.hrm.SimulatedHeartRateSource
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.music.WorkoutMusicPlayer
import com.telecomprofi.japanesewalk.trainer.TrainerControl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "WorkoutService"

/**
 * Keeps a walk alive with the screen off: owns the engine, the strap connection,
 * the music player and the voice coach for the duration of one session.
 *
 * Declared as a media-playback foreground service because it streams the
 * pace-matched playlist for the whole workout.
 */
class WorkoutService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.telecomprofi.japanesewalk.START"
        const val ACTION_PAUSE = "com.telecomprofi.japanesewalk.PAUSE"
        const val ACTION_RESUME = "com.telecomprofi.japanesewalk.RESUME"
        const val ACTION_STOP = "com.telecomprofi.japanesewalk.STOP"
        const val ACTION_SKIP = "com.telecomprofi.japanesewalk.SKIP"
        const val ACTION_NEXT_TRACK = "com.telecomprofi.japanesewalk.NEXT_TRACK"
        const val ACTION_TOGGLE_MUSIC = "com.telecomprofi.japanesewalk.TOGGLE_MUSIC"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, WorkoutService::class.java).setAction(ACTION_START)
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, WorkoutService::class.java).setAction(action))
        }
    }

    private var engine: WorkoutEngine? = null
    private var coach: VoiceCoach? = null
    private var music: WorkoutMusicPlayer? = null
    private var source: HeartRateSource? = null
    private var location: LocationTracker? = null
    private var trainer: TrainerControl? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var interruptions: AudioInterruptions? = null
    private var notificationTick = 0
    private var musicMuted = false
    /** Whether the user had already muted the music when a call arrived. */
    private var mutedBeforeCall = false
    /**
     * beginSession() suspends twice before assigning [engine], so a null check
     * alone is not a lock — two quick Start taps built two engines, two straps
     * and two voice coaches, and the first became an unstoppable orphan.
     */
    private var starting = false

    /**
     * Hand the soundtrack over to another app, or take it back. Pausing rather
     * than releasing keeps the chosen track and cadence, so unmuting picks up
     * where it left off.
     */
    private fun toggleMusic() {
        val player = music ?: return
        musicMuted = !musicMuted
        player.setMuted(musicMuted)
        WorkoutHub.setMusicMuted(player.isMuted)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                promoteToForeground(WorkoutHub.state.value)
                if (engine == null && !starting) beginSession()
            }
            ACTION_PAUSE -> engine?.pause()
            ACTION_RESUME -> engine?.resume()
            ACTION_SKIP -> engine?.skipSegment()
            ACTION_NEXT_TRACK -> music?.nextTrack()
            ACTION_TOGGLE_MUSIC -> toggleMusic()
            ACTION_STOP -> engine?.stop() ?: stopSelf()
        }
        return START_STICKY
    }

    /**
     * Declaring the location type without holding the permission throws on
     * Android 14+, so the type is assembled from what has actually been granted.
     */
    private fun foregroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        // Claim the location type only when a tracker is actually running: the
        // type is a declaration of what the service does, not of what it may do.
        if (granted && location != null) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return type
    }

    private fun promoteToForeground(state: WorkoutUiState) {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_WORKOUT,
            Notifications.workout(this, state, musicMuted),
            foregroundServiceType(),
        )
    }

    private fun beginSession() {
        Graph.init(this)
        starting = true
        lifecycleScope.launch {
            try {
                beginSessionInner()
            } finally {
                // Cleared on every exit path, including a throw from reading
                // settings or the track table. Without this a single failed
                // start wedged the guard and no later Start would be accepted.
                starting = false
            }
        }
    }

    private suspend fun beginSessionInner() {
        run {
            val settings = Graph.container.prefs.settings.first()
            WorkoutHub.setInfo(null)
            acquireWakeLock()
            val player = buildMusic(settings)
            WorkoutHub.setExternalMusic(settings.music.enabled && settings.music.useExternalPlayer)
            val voice = VoiceCoach(
                context = this@WorkoutService,
                // Only when the music is somebody else's. Asking the system to
                // duck while we own the player makes it duck *us*: our ExoPlayer
                // holds audio focus too, so it hears the transient loss, drops to
                // a fifth of its volume, and a track that starts during the cue
                // never gets the focus-gain that would lift it again. That is the
                // bug where the music stayed quiet until you skipped the track.
                // We already lower our own music in duck(), which needs no focus.
                duckViaAudioFocus = player == null,
            ) { speaking -> player?.duck(speaking) }
            coach = voice
            music = player

            val hrSource = buildSource(settings)
            source = hrSource
            // Indoors there is nothing for GPS to measure, so it is not started
            // at all — which is also why the foreground-service type below is
            // derived from whether a tracker exists, not from the permission.
            // Indoors, and on a trainer, there is nothing for GPS to measure.
            val tracker = LocationTracker(this@WorkoutService)
                .takeIf { it.hasPermission && !settings.indoorMode && settings.mode.usesGps }
            location = tracker

            // The trainer is only built for a ride, and only once one is paired;
            // without it a cycling session still runs, coached rather than driven.
            val bike = if (settings.mode == WorkoutMode.CYCLE && settings.cycle.deviceAddress != null) {
                TrainerControl(this@WorkoutService).apply {
                    setDevice(settings.cycle.deviceAddress, settings.cycle.deviceName)
                }
            } else {
                null
            }
            trainer = bike
            if (settings.mode == WorkoutMode.CYCLE && bike == null) {
                WorkoutHub.setInfo("No trainer paired — pair one in Settings to set resistance")
            }

            val workoutEngine = WorkoutEngine(
                scope = lifecycleScope,
                profile = settings.profile,
                protocol = settings.protocol,
                coachSettings = settings.coach,
                coolDownSettings = settings.coolDown,
                mode = settings.mode,
                cycle = settings.cycle,
                trainer = bike,
                source = hrSource,
                coach = voice,
                music = player,
                location = tracker,
                onFinished = { result -> onSessionFinished(result, settings) },
            )
            engine = workoutEngine
            WorkoutHub.attach(workoutEngine)
            player?.let { p ->
                followPlaylist(p, settings, workoutEngine)
                // Anything wrong with the music says so on the walk screen. It
                // used to be silent in both senses.
                lifecycleScope.launch {
                    p.problem.collect { message ->
                        if (message != null) WorkoutHub.setInfo(message)
                    }
                }
                // A pause from the notification shade is a mute as far as the
                // rest of the app is concerned, so the UI button agrees with it.
                lifecycleScope.launch {
                    p.mutedState.collect { muted ->
                        musicMuted = muted
                        WorkoutHub.setMusicMuted(muted)
                    }
                }
            }

            lifecycleScope.launch {
                workoutEngine.state.collect { state ->
                    WorkoutHub.publish(state)
                    // Refresh the notification about twice a second's worth of
                    // useful change, not on every single emission.
                    if (notificationTick++ % 2 == 0) {
                        try {
                            promoteToForeground(state)
                        } catch (e: Exception) {
                            Log.w(TAG, "notification update failed", e)
                        }
                    }
                }
            }

            workoutEngine.start()
            // Only once the engine is RUNNING: started any earlier, a call
            // already in progress would set the interrupted flag against an
            // IDLE engine, which pauses nothing and can never be undone.
            startCallWatch()
        }
    }

    /**
     * Pause the walk, and the coach, for the duration of a call.
     *
     * The voice cues are the real problem: they use navigation-guidance audio
     * attributes, which the system is happy to mix straight into a call, so
     * without this the other party hears "fast interval, set three of five".
     */
    private fun startCallWatch() {
        if (interruptions != null) return
        interruptions = AudioInterruptions(
            context = this,
            onCallStarted = {
                coach?.setSuppressed(true)
                // Remember whether the music was already off, or hanging up
                // would start the playlist over someone's podcast.
                mutedBeforeCall = music?.isMuted == true
                music?.setMuted(true)
                engine?.interrupt()
            },
            onCallEnded = {
                coach?.setSuppressed(false)
                music?.setMuted(mutedBeforeCall)
                engine?.endInterruption()
            },
        ).also { it.start() }
    }

    /**
     * The player is created whenever the app is meant to be the music source,
     * even with an empty playlist — that way tracks added mid-session can start
     * playing without restarting the walk.
     */
    private suspend fun buildMusic(settings: AppSettings): WorkoutMusicPlayer? {
        if (!settings.music.enabled) return null
        if (settings.music.useExternalPlayer) return null
        val tracks = Graph.container.db.trackDao().allOnce()
        return WorkoutMusicPlayer(this).apply {
            prepare(tracks, settings.music)
            // A file this device can never decode is removed from the queue
            // rather than skipped again on every future walk.
            onTrackUnplayable = { uri, reason ->
                lifecycleScope.launch {
                    Log.w(TAG, "dropping unplayable track ($reason): $uri")
                    Graph.container.db.trackDao().setMissing(uri, true)
                }
            }
        }
    }

    /**
     * Follow the playlist for the life of the session. Adding tracks on the
     * Music tab takes effect on the next emission; the banner is cleared as soon
     * as there is something to play, rather than lingering from session start.
     */
    private fun followPlaylist(player: WorkoutMusicPlayer, settings: AppSettings, engine: WorkoutEngine) {
        lifecycleScope.launch {
            Graph.container.db.trackDao().all().collect { tracks ->
                val hadNothing = !player.hasMusic
                player.prepare(tracks, settings.music)
                if (tracks.isEmpty()) {
                    WorkoutHub.setInfo("No tracks queued — add some on the Music tab")
                } else {
                    WorkoutHub.setInfo(null)
                    if (hadNothing) {
                        // First tracks of the session: start them at the pace the
                        // current bout is asking for.
                        engine.currentCadenceSpm()?.let { player.setTargetCadence(it, switchTrack = true) }
                    }
                }
            }
        }
    }

    private fun buildSource(settings: AppSettings): HeartRateSource {
        if (settings.hrm.simulate || settings.hrm.deviceAddress == null) {
            if (!settings.hrm.simulate) {
                WorkoutHub.setInfo("No strap paired – using simulated heart rate")
            }
            return SimulatedHeartRateSource(
                scope = lifecycleScope,
                restingHr = settings.profile.restingHr,
                targetProvider = { WorkoutHub.currentTargetHr() },
            )
        }
        return BleHeartRateMonitor(this).apply {
            setDevice(settings.hrm.deviceAddress, settings.hrm.deviceName)
        }
    }

    private suspend fun onSessionFinished(result: SessionResult, settings: AppSettings) {
        val outcome = Graph.container.workoutRepository.save(result)
        WorkoutHub.publishOutcome(outcome)
        if (settings.coach.voiceEnabled && outcome.saved) {
            coach?.say(
                CoachScript.finished(
                    minutes = result.minutes,
                    kcal = result.kcal,
                    adherence = result.adherencePercent,
                    weekMinutes = outcome.week.minutes,
                    weekTarget = outcome.week.targetMinutes,
                    coolDownMinutes = result.coolDownMinutes,
                ),
                interrupt = true,
            )
        }
        // Give the summary a moment to finish speaking before tearing down TTS.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(if (settings.coach.voiceEnabled) 9_000 else 500)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JapaneseWalk::session").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock unavailable", e)
        }
    }

    override fun onDestroy() {
        interruptions?.stop()
        interruptions = null
        engine?.let { if (it.isSessionActive) it.stop() }
        // Clear the published state as well as the engine reference, so a later
        // visit to the Zones tab starts idle instead of replaying FINISHED.
        WorkoutHub.reset()
        WorkoutHub.setExternalMusic(false)
        source?.stop()
        location?.stop()
        trainer?.stop()
        trainer = null
        music?.release()
        coach?.shutdown()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release failed", e)
        }
        wakeLock = null
        super.onDestroy()
    }
}
