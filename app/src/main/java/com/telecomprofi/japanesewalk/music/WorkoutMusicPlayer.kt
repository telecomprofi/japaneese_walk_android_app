package com.telecomprofi.japanesewalk.music

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.telecomprofi.japanesewalk.core.LoudnessNormaliser
import com.telecomprofi.japanesewalk.data.db.TrackEntity
import com.telecomprofi.japanesewalk.data.prefs.MusicSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

private const val TAG = "MusicPlayer"

/** How hard the player tries to read a file before it reports a failure. */
private const val IO_RETRIES = 6

/** No spoken cue in this app runs longer than this. */
private const val DUCK_LIMIT_MS = 20_000L

/**
 * How many times one track is asked to play before the player gives up on it
 * for this session. Three, because the failures are transient and the second
 * attempt usually works.
 */
private const val RETRY_LIMIT = 3

/** Long enough for a sleeping SD card to wake, short enough to be unnoticed. */
private const val RETRY_DELAY_MS = 700L

/** How many songs back the player remembers, for avoiding near-duplicates. */
private const val SONG_MEMORY = 40

data class NowPlaying(
    val title: String,
    val artist: String?,
    val trackBpm: Double,
    val effectiveBpm: Double,
    val targetBpm: Int,
    val speed: Float,
    val multiplier: Double,
    /** Measured loudness of this track, LUFS; 0.0 when it has none. */
    val loudnessLufs: Double = 0.0,
    /** Attenuation applied to level it with the rest of the playlist, dB. */
    val gainDb: Double = 0.0,
) {
    val stretchPercent: Int get() = ((speed - 1f) * 100).roundToInt()
    val levelled: Boolean get() = gainDb < -0.4 || gainDb > 0.4
}

/**
 * Pace-matched music, the Lolo BeatBurn way: each interval gets a track whose
 * tempo is close to the target walking cadence, then the track is time-stretched
 * (pitch-corrected, via ExoPlayer's Sonic pipeline) to sit exactly on it.
 */
@OptIn(markerClass = [UnstableApi::class])
/** A once-a-second picture of the player, for the screen and for diagnosis. */
data class MusicStatus(
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** The volume actually applied, 0-100 — levelling, ducking and all. */
    val volumePercent: Int = 0,
    val ducked: Boolean = false,
    val muted: Boolean = false,
) {
    val hasDuration: Boolean get() = durationMs > 0
}

class WorkoutMusicPlayer(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    private var tracks: List<TrackEntity> = emptyList()
    private var settings: MusicSettings = MusicSettings()
    private val recent = ArrayDeque<String>()
    /**
     * The songs behind the recent URIs. A playlist that holds the radio edit,
     * the extended mix and a sped-up upload of one song holds three different
     * URIs and one piece of music, and hearing them in a row is the complaint
     * this answers.
     */
    private val recentSongs = ArrayDeque<String>()
    private var targetBpm: Int = 0
    private var currentUri: String? = null
    private var ducked = false
    private var muted = false
    /**
     * The level the playlist is being held at — its median loudness — and the
     * attenuation the current track needs to reach it. Recomputed whenever the
     * playlist changes.
     */
    private var levelReference: Double? = null
    private var trackGain = 1f

    /**
     * The only way to get above unity gain. The player's own volume is capped at
     * 1.0, so without this the quiet half of a playlist could not be lifted and
     * the only way to match tracks would be to pull the loud half a long way
     * down — which is exactly what made the app half as loud as everything else.
     * Unavailable on some devices, in which case levelling is attenuation-only.
     */
    private var enhancer: LoudnessEnhancer? = null
    /**
     * Bumped by every fade. A step whose generation is stale does nothing, which
     * is what stops a track crossfade and a coaching duck from overwriting each
     * other — they used to collide at exactly the moment a cue was spoken.
     */
    private var fadeGeneration = 0

    /**
     * ExoPlayer may only be touched from the thread it was built on. The voice
     * coach's callbacks arrive on a TTS binder thread, so every entry point that
     * reaches the player goes through here first. Reading `player.volume` from
     * the wrong thread throws inside a binder transaction, where the exception is
     * swallowed and logged — which is exactly how a duck could be applied and
     * never taken off again.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }

    /**
     * A duck is always meant to be brief. If the engine never tells us the cue
     * finished — a lost TTS callback, a speech engine killed mid-utterance — the
     * music would stay quiet for the rest of the session, so it is lifted anyway.
     */
    private val unduckWatchdog = Runnable {
        if (ducked) {
            Log.w(TAG, "duck outlasted the cue; lifting it")
            ducked = false
            settleTo(targetVolume(), 400)
        }
    }

    /** Total levelling gain applied to the current track, dB, for the readout. */
    private var currentGainDb = 0.0

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /**
     * Mirrors [muted] so the rest of the app can follow a pause that came from
     * the notification shade rather than from our own buttons.
     */
    /**
     * Why there is no music, in words a person can act on. Silence used to be
     * the only symptom of a failed track — the player would go idle and stay
     * there, and every later interval change and every skip would fail the same
     * silent way. Whatever goes wrong now says so.
     */
    /**
     * What the player is actually doing, once a second. Without this a silent
     * walk is unreadable: a track whose title changed but whose volume is a
     * tenth of a per cent looks exactly like a track that is paused, and the two
     * have entirely different causes.
     */
    private val _status = MutableStateFlow(MusicStatus())
    val status: StateFlow<MusicStatus> = _status.asStateFlow()

    private val statusTicker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                _status.value = MusicStatus(
                    playing = p.isPlaying,
                    positionMs = p.currentPosition.coerceAtLeast(0),
                    durationMs = p.duration.takeIf { it > 0 } ?: 0L,
                    volumePercent = (p.volume * 100).roundToInt(),
                    ducked = ducked,
                    muted = muted,
                )
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Tracks that would not open this session; not chosen again until restart. */
    private val failed = mutableSetOf<String>()

    /**
     * Called with a track that can never play on this device — a format Android
     * has no decoder for, or a file that has gone. The service drops it from
     * the playlist for good; skipping it once per walk forever is not a fix.
     */
    var onTrackUnplayable: ((uri: String, reason: String) -> Unit)? = null

    /** How many were dropped this session, so the banner can say so once. */
    private var droppedCount = 0

    /**
     * How many times each track has refused to open *this session*.
     *
     * It matters because the failures turned out to be transient: a track that
     * is skipped on one walk plays perfectly on the next, and a different set
     * fails each time. That is the signature of slow or sleeping storage — an
     * SD card, in this case — not of a broken file, so the first thing to do
     * with a failure is to try the same track again rather than to conclude
     * anything about it.
     */
    private val attempts = mutableMapOf<String, Int>()
    private var consecutiveFailures = 0

    private val _mutedState = MutableStateFlow(false)
    val mutedState: StateFlow<Boolean> = _mutedState.asStateFlow()

    /**
     * Publishes the playlist to the system, which is what puts a card with
     * play/pause and next in the notification shade and on the lock screen.
     */
    private var session: MediaSession? = null

    /**
     * Session-long audio focus, taken once. Other apps yield to the walk; a
     * transient loss is ignored, because in this app the usual cause of one is
     * our own coach clearing its throat.
     */
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    /** True when the music was silenced by another app taking over for good. */
    private var yielded = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Something else has taken the audio for good — another music
                // app, most likely. Go quiet rather than fight it.
                yielded = true
                onMain { player?.pause() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (yielded) {
                    yielded = false
                    onMain { if (!muted) player?.play() }
                }
            }
            // AUDIOFOCUS_LOSS_TRANSIENT and *_CAN_DUCK are deliberately ignored:
            // the coach's own cues arrive as exactly these, and a walk should
            // not stop for them.
            else -> Unit
        }
    }

    private fun takeFocus() {
        val manager = audioManager ?: return
        if (focusRequest != null) return
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } catch (e: Exception) {
            Log.w(TAG, "audio focus request failed", e)
            focusRequest = null
        }
    }

    private fun releaseFocus() {
        val manager = audioManager ?: return
        focusRequest?.let {
            try {
                manager.abandonAudioFocusRequest(it)
            } catch (e: Exception) {
                Log.w(TAG, "abandoning focus failed", e)
            }
        }
        focusRequest = null
    }

    val hasMusic: Boolean get() = tracks.isNotEmpty()

    /**
     * Point the player at a playlist. Safe to call repeatedly during a session:
     * the engine re-reads the track table whenever it changes, so tracks added
     * on the Music tab are picked up without restarting the walk.
     */
    fun prepare(tracks: List<TrackEntity>, settings: MusicSettings) {
        this.tracks = tracks
        this.settings = settings
        levelReference = if (settings.normaliseLoudness) {
            LoudnessNormaliser.reference(tracks.map { it.loudnessLufs })
        } else {
            null
        }
        checkAccess()
        // A playlist change can move the target, so the track already playing
        // needs its attenuation recomputed rather than waiting for the next bout.
        currentUri?.let { uri ->
            tracks.firstOrNull { it.uri == uri }?.let { applyLevelling(it, fade = true) }
        }
        if (player == null) {
            val sessionId = try {
                Util.generateAudioSessionIdV21(context)
            } catch (e: Exception) {
                C.AUDIO_SESSION_ID_UNSET
            }
            player = ExoPlayer.Builder(context)
                // Retry a failed read six times instead of three before giving
                // up on it. The failures seen in the wild are transient — an SD
                // card waking up, a provider briefly refusing — and a track
                // that is skipped on one walk plays fine on the next, so the
                // cheapest fix is to let the player try harder before it
                // reports anything at all.
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(context)
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(IO_RETRIES))
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    // handleAudioFocus = false, and this is load-bearing.
                    //
                    // The voice coach speaks through the platform TTS engine,
                    // and that engine asks the system for audio focus of its
                    // own for the duration of every cue. A player managing its
                    // own focus hears that as an interruption and pauses — for
                    // its own app's coach — and when the grant that comes back
                    // is a plain loss rather than a transient one, it never
                    // resumes. That was the walk where the music stopped at an
                    // interval change and only came back if you skipped the
                    // track: the skip re-prepared the player, which re-took
                    // focus. Focus for the session is requested once, below,
                    // and a real interruption (a call) is caught by the audio
                    // *mode* watcher in the service, which cannot be confused
                    // by this app's own speech.
                    /* handleAudioFocus = */ false,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
                .also { p ->
                    // A session id of our own, so the loudness effect can be
                    // attached to it before anything plays: left to itself
                    // ExoPlayer does not settle on one until the first track
                    // starts. This is a method on the player, not the builder.
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                        try {
                            p.audioSessionId = sessionId
                        } catch (e: Exception) {
                            Log.w(TAG, "could not set audio session id", e)
                        }
                    }
                    p.repeatMode = Player.REPEAT_MODE_OFF
                    takeFocus()
                    handler.post(statusTicker)
                    p.volume = targetVolume()
                    p.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) nextTrack()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            // Music is coming out of the phone: whatever the
                            // last complaint was, it no longer applies.
                            if (isPlaying) {
                                consecutiveFailures = 0
                                currentUri?.let { attempts.remove(it) }
                                if (_problem.value != null) _problem.value = null
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            onTrackFailed(error)
                        }
                    })
                }
            enhancer = if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                try {
                    LoudnessEnhancer(sessionId).apply { enabled = true }
                } catch (e: Exception) {
                    // Not every device ships the effect; levelling then only
                    // ever turns tracks down, which is still an improvement.
                    Log.w(TAG, "loudness enhancer unavailable", e)
                    null
                }
            } else {
                null
            }
            session = try {
                // The session is handed a wrapper, not the raw player, so that
                // play/pause from the shade, the lock screen or a headset button
                // goes through the app's own mute flag. Pausing the ExoPlayer
                // directly would not stick: the next interval change picks a
                // track and calls play() again, which reads as the app ignoring
                // you. Routing through setMuted() makes the external control and
                // the in-app Mute button the same switch.
                val sessionPlayer = object : ForwardingPlayer(player!!) {
                    override fun play() = this@WorkoutMusicPlayer.setMuted(false)

                    override fun pause() = this@WorkoutMusicPlayer.setMuted(true)

                    override fun setPlayWhenReady(playWhenReady: Boolean) =
                        this@WorkoutMusicPlayer.setMuted(!playWhenReady)
                }
                MediaSession.Builder(context, sessionPlayer).setId("japanese-walk").build()
            } catch (e: Exception) {
                // A session is a nicety; never let it take the workout down.
                null
            }
        }
    }

    /**
     * Point the music at a new cadence.
     *
     * @param switchTrack true at interval boundaries: pick a fresh track that
     *        fits the new pace. False mid-interval: just nudge the speed.
     */
    fun setTargetCadence(bpm: Int, switchTrack: Boolean) {
        targetBpm = bpm
        if (!settings.enabled || tracks.isEmpty()) return
        if (switchTrack || _nowPlaying.value == null) {
            crossfadeTo(chooseNext())
        } else {
            retune()
        }
    }

    /**
     * The next track: one of the ones that fit this cadence, at random.
     *
     * Two things had to change to stop a thirteen-hundred track playlist
     * sounding like ten songs. The choice is drawn from a pool of good fits
     * rather than being the single best fit, and the memory of what has been
     * played is now a quarter of the playlist rather than eight tracks — which
     * is what actually decides how long it takes to hear a repeat.
     *
     * Tracks whose tempo has never been measured are not left out. With a large
     * folder, analysis takes a while, and a rider who has queued a thousand
     * songs and measured thirty should still hear a thousand songs; the
     * unmeasured ones simply play at their own speed.
     */
    private fun chooseNext(): TrackEntity? {
        if (tracks.isEmpty()) return null
        // Everything failed: clear the slate rather than return nothing, so a
        // re-granted permission starts working without restarting the app.
        if (failed.size >= tracks.size) failed.clear()
        val playable = tracks.filter {
            it.uri !in failed && PlayableFormats.isPlayable(it.fileName)
        }.ifEmpty { tracks.filter { t -> PlayableFormats.isPlayable(t.fileName) } }
        if (playable.isEmpty()) return null

        // A track whose tempo was measured with little confidence is played, but
        // not *matched*: claiming 103 for a 155 BPM song is worse than claiming
        // nothing, because it lands a fast song in the slow half of the walk.
        val fresh = playable.filterNot {
            TrackDeduper.songKey(it.title, it.fileName) in recentSongs
        }.ifEmpty { playable }
        val analysed = fresh.filter { it.tempoTrusted }
        val unanalysed = fresh.filter { !it.tempoTrusted }

        // Matching is off, or nothing has been measured: shuffle, do not rotate.
        if (!settings.matchBpm || targetBpm <= 0 || analysed.isEmpty()) {
            return randomFrom(fresh) ?: fresh.firstOrNull()
        }

        val match = TrackMatcher.pickRandom(
            candidates = analysed.map { MatchCandidate(it.uri, it.bpm) },
            targetBpm = targetBpm,
            maxStretchPercent = settings.maxStretchPercent,
            allowOctave = settings.allowOctaveMatch,
            recentlyPlayed = recent,
        )

        // A measured track that fits is the best outcome, but not at the cost of
        // never playing the rest of the library: when most of the playlist has
        // no tempo yet, it gets a proportional share of the turns.
        val unmeasuredShare = unanalysed.size.toDouble() / fresh.size.coerceAtLeast(1)
        val takeUnmeasured = unanalysed.isNotEmpty() &&
            (match == null || java.util.concurrent.ThreadLocalRandom.current().nextDouble() < unmeasuredShare * 0.5)
        if (takeUnmeasured) {
            randomFrom(unanalysed)?.let { return it }
        }

        match?.let { result -> tracks.firstOrNull { it.uri == result.id } }?.let { return it }
        return randomFrom(fresh) ?: fresh.firstOrNull()
    }

    /** A track nobody has heard lately, chosen at random. */
    private fun randomFrom(pool: List<TrackEntity>): TrackEntity? {
        if (pool.isEmpty()) return null
        val fresh = pool.filterNot { it.uri in recent }.ifEmpty { pool }
        return fresh[java.util.concurrent.ThreadLocalRandom.current().nextInt(fresh.size)]
    }

    private fun playbackFor(track: TrackEntity): Pair<Float, MatchResult?> {
        if (!settings.matchBpm || track.bpm <= 0.0 || targetBpm <= 0) return 1f to null
        val match = TrackMatcher.pick(
            candidates = listOf(MatchCandidate(track.uri, track.bpm)),
            targetBpm = targetBpm,
            maxStretchPercent = settings.maxStretchPercent,
            allowOctave = settings.allowOctaveMatch,
        ) ?: return 1f to null
        return match.speed.toFloat() to match
    }

    /**
     * A track that will not open.
     *
     * The usual cause is not a broken file: it is Android dropping the
     * permission on the folder — which it does on some devices after a reboot or
     * an update — and once that has happened *every* track fails, so the walk
     * runs in silence and no amount of skipping helps. That case is named
     * explicitly, because the fix (pick the folder again) is not one anybody
     * would guess from silence.
     */
    /**
     * Does this app still hold permission on the files it is about to play?
     *
     * Worth asking before the first note rather than after the first failure:
     * the answer is the difference between "one track is broken" and "the folder
     * grant is gone", and only the second needs the rider to do something.
     */
    private fun checkAccess() {
        if (tracks.isEmpty()) return
        val persisted = try {
            context.contentResolver.persistedUriPermissions.map { it.uri.toString() }
        } catch (e: Exception) {
            Log.w(TAG, "could not read persisted permissions", e)
            return
        }
        // A track is reachable if its own URI was persisted, or it sits under a
        // persisted tree — document URIs under a tree keep the tree's prefix.
        val reachable = tracks.count { track -> persisted.any { track.uri.startsWith(it) } }
        _problem.value = when {
            persisted.isEmpty() || reachable == 0 ->
                "Android has forgotten the permission on your music folder. " +
                    "Open the Music tab and pick the folder again — the playlist is kept."
            reachable < tracks.size ->
                "${tracks.size - reachable} of ${tracks.size} tracks are no longer reachable; " +
                    "re-pick the folder on the Music tab to restore them."
            else -> null
        }
    }

    private fun onTrackFailed(error: PlaybackException) {
        val uri = currentUri
        val track = tracks.firstOrNull { it.uri == uri }
        val title = track?.displayName ?: _nowPlaying.value?.title ?: "that track"
        val extension = track?.fileName?.let { PlayableFormats.extensionOf(it) }.orEmpty()
        Log.w(TAG, "playback failed for $title: ${error.errorCodeName}", error)

        val lostPermission = error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            generateSequence(error.cause) { it.cause }.any { it is SecurityException }

        // Only a format this device has no decoder for is hopeless, and that is
        // decided by the file's extension, which cannot change between walks.
        // Every other failure — a short read, a parse that fell over, a file
        // that was briefly not there — is treated as bad luck and retried,
        // because that is what the evidence says it is.
        val hopeless = track != null && !PlayableFormats.isPlayable(track.fileName)

        val tries = if (uri == null) RETRY_LIMIT else (attempts[uri] ?: 0) + 1
        if (uri != null) attempts[uri] = tries

        if (!lostPermission && !hopeless && tries < RETRY_LIMIT && track != null) {
            // Quietly, and without touching the playlist: most of these come
            // back on the second attempt and the walk never notices.
            Log.i(TAG, "retrying $title (attempt ${tries + 1} of $RETRY_LIMIT)")
            handler.postDelayed({ crossfadeTo(track) }, RETRY_DELAY_MS)
            return
        }

        if (uri != null) failed += uri
        consecutiveFailures++

        val named = if (extension.isNotEmpty()) "$title (.$extension)" else title
        _problem.value = when {
            lostPermission ->
                "Android has forgotten the permission on your music folder. " +
                    "Open the Music tab and pick the folder again — the playlist is kept."
            hopeless -> {
                val format = track?.fileName?.let { PlayableFormats.unplayableName(it) }
                droppedCount++
                onTrackUnplayable?.invoke(uri.orEmpty(), error.errorCodeName)
                if (format != null) {
                    "Dropped $named — the player cannot read $format files."
                } else {
                    "Dropped $named — this device has no decoder for it."
                }
            }
            // One track that would not open after three tries is not worth a
            // banner; a run of them means something is actually wrong.
            consecutiveFailures >= 3 ->
                "Music is struggling to read your files — ${error.errorCodeName}. " +
                    "If this keeps up, re-pick the folder on the Music tab."
            else -> null
        }

        if (!lostPermission && consecutiveFailures < 4) {
            handler.postDelayed({ nextTrack() }, 400)
        }
    }


    private fun crossfadeTo(track: TrackEntity?) {
        val p = player ?: return
        if (track == null) return
        val start = {
            val (speed, match) = playbackFor(track)
            applyLevelling(track, fade = false)
            p.setMediaItem(
                MediaItem.Builder()
                    .setUri(track.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.displayName)
                            .setArtist(track.artist)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
            )
            p.playbackParameters = PlaybackParameters(speed, 1.0f)
            p.volume = 0f
            p.prepare()
            if (!muted) p.play()
            currentUri = track.uri
            rememberRecent(track.uri)
            _nowPlaying.value = NowPlaying(
                title = track.displayName,
                artist = track.artist,
                trackBpm = track.bpm,
                effectiveBpm = match?.effectiveBpm ?: track.bpm,
                targetBpm = targetBpm,
                speed = speed,
                multiplier = match?.multiplier ?: 1.0,
                loudnessLufs = track.loudnessLufs,
                gainDb = currentGainDb,
            )
            settleTo(targetVolume(), 700)
            // If the track was asked to play and is still not playing a second
            // and a half later, ask again: silence here is the one failure the
            // rider cannot work around without reaching for the phone.
            handler.postDelayed({
                val still = player ?: return@postDelayed
                if (!muted && !yielded && !still.isPlaying &&
                    still.playbackState != Player.STATE_IDLE
                ) {
                    Log.w(TAG, "track did not start; asking again")
                    still.play()
                }
            }, 1_500)
        }
        if (p.isPlaying) {
            fadeTo(0f, 500)
            handler.postDelayed({ start() }, 520)
        } else {
            start()
        }
    }

    private fun retune() {
        val p = player ?: return
        val playing = _nowPlaying.value ?: return
        val track = tracks.firstOrNull { it.uri == currentUri } ?: return
        val (speed, match) = playbackFor(track)
        p.playbackParameters = PlaybackParameters(speed, 1.0f)
        _nowPlaying.value = playing.copy(
            speed = speed,
            targetBpm = targetBpm,
            effectiveBpm = match?.effectiveBpm ?: playing.effectiveBpm,
        )
    }

    fun nextTrack() = onMain {
        crossfadeTo(chooseNext())
    }

    fun play() = onMain {
        if (!muted) player?.play()
    }

    /**
     * The single source of truth for how loud the player should be right now:
     * silent when muted, ducked while the coach is speaking, otherwise the
     * volume the user chose. Every volume change goes through this.
     */
    private fun targetVolume(): Float = when {
        muted -> 0f
        ducked -> baseVolume() * (settings.duckPercent.coerceIn(0, 90) / 100f)
        else -> baseVolume()
    }

    /**
     * Work out how far this track has to come down to sit level with the rest of
     * the playlist. Attenuation only — see [LoudnessNormaliser].
     */
    private fun applyLevelling(track: TrackEntity, fade: Boolean) {
        val gainDb = if (settings.normaliseLoudness) {
            LoudnessNormaliser.gainDb(
                lufs = track.loudnessLufs,
                reference = levelReference,
                maxCutDb = settings.maxCutDb,
                maxBoostDb = settings.maxBoostDb,
            )
        } else {
            0.0
        }
        currentGainDb = gainDb
        // Down through the player's volume, up through the audio effect.
        try {
            enhancer?.setTargetGain(LoudnessNormaliser.boostMillibels(gainDb))
        } catch (e: Exception) {
            Log.w(TAG, "boost failed", e)
        }
        val factor = LoudnessNormaliser.attenuationFactor(gainDb)
        if (factor == trackGain) return
        trackGain = factor
        if (fade) fadeTo(targetVolume(), 250)
    }

    /**
     * Hand the soundtrack over to another app, or take it back. The flag lives
     * here rather than in the service so that an interval change — which picks a
     * new track and starts it — cannot silently un-mute.
     */
    fun setMuted(value: Boolean) = onMain {
        muted = value
        _mutedState.value = value
        if (value) {
            player?.pause()
        } else {
            // Through fadeTo, not a bare volume assignment: a fade still in
            // flight would otherwise overwrite it a few milliseconds later.
            fadeTo(targetVolume(), 0)
            player?.play()
        }
    }

    val isMuted: Boolean get() = muted

    fun pause() = onMain {
        player?.pause()
    }

    /** Lower the music while the voice coach talks. */
    fun duck(enable: Boolean) = onMain {
        handler.removeCallbacks(unduckWatchdog)
        if (ducked != enable) {
            ducked = enable
            // Drop fast so the first syllable is already audible; come back gently.
            settleTo(targetVolume(), if (enable) 120 else 400)
        } else if (!enable) {
            // Already un-ducked as far as this flag is concerned, but a track that
            // started mid-cue may still be sitting low. Re-assert, cheaply.
            settleTo(targetVolume(), 0)
        }
        if (enable) handler.postDelayed(unduckWatchdog, DUCK_LIMIT_MS)
    }

    fun setVolumePercent(percent: Int) = onMain {
        settings = settings.copy(volumePercent = percent.coerceIn(0, 100))
        settleTo(targetVolume(), 150)
    }

    private fun baseVolume(): Float =
        (settings.volumePercent.coerceIn(0, 100) / 100f) * trackGain

    /**
     * A fade that ends on the exact number. [fadeTo] walks in twelve steps and
     * can be left a hair short by rounding, and — more to the point — a fade
     * that was overtaken never arrives at all. The final write lands after the
     * last step, so whatever else happened, the volume is right afterwards.
     */
    private fun settleTo(target: Float, durationMs: Long) {
        fadeTo(target, durationMs)
        val generation = fadeGeneration
        handler.postDelayed({
            if (generation == fadeGeneration) player?.volume = target.coerceIn(0f, 1f)
        }, durationMs + 40)
    }

    private fun fadeTo(target: Float, durationMs: Long) {
        val p = player ?: return
        val generation = ++fadeGeneration
        val steps = 12
        val start = p.volume
        val delta = (target - start) / steps
        for (i in 1..steps) {
            handler.postDelayed({
                // A fade started later has already taken over; stay out of its way.
                if (generation == fadeGeneration) {
                    player?.volume = (start + delta * i).coerceIn(0f, 1f)
                }
            }, durationMs / steps * i)
        }
    }

    /**
     * How far back "recently played" goes. Eight was the whole bug: with a
     * deterministic best-match, the rotation is exactly as long as this memory.
     * A quarter of the playlist, capped, means a thousand-track folder gets
     * through two hundred and fifty songs before anything comes round again.
     */
    private fun rememberRecent(uri: String) {
        tracks.firstOrNull { it.uri == uri }?.let { track ->
            val song = TrackDeduper.songKey(track.title, track.fileName)
            recentSongs.remove(song)
            recentSongs.addFirst(song)
            while (recentSongs.size > SONG_MEMORY) recentSongs.removeLast()
        }
        recent.remove(uri)
        recent.addFirst(uri)
        val keep = (tracks.size / 4).coerceIn(8, 250)
        while (recent.size > keep) recent.removeLast()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        releaseFocus()
        try {
            enhancer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "enhancer release failed", e)
        }
        enhancer = null
        session?.release()
        session = null
        player?.release()
        player = null
        _nowPlaying.value = null
    }
}
