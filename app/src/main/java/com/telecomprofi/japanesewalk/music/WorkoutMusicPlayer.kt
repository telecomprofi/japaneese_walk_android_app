package com.telecomprofi.japanesewalk.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.telecomprofi.japanesewalk.data.db.TrackEntity
import com.telecomprofi.japanesewalk.data.prefs.MusicSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class NowPlaying(
    val title: String,
    val artist: String?,
    val trackBpm: Double,
    val effectiveBpm: Double,
    val targetBpm: Int,
    val speed: Float,
    val multiplier: Double,
) {
    val stretchPercent: Int get() = ((speed - 1f) * 100).roundToInt()
}

/**
 * Pace-matched music, the Lolo BeatBurn way: each interval gets a track whose
 * tempo is close to the target walking cadence, then the track is time-stretched
 * (pitch-corrected, via ExoPlayer's Sonic pipeline) to sit exactly on it.
 */
@OptIn(markerClass = [UnstableApi::class])
class WorkoutMusicPlayer(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    private var tracks: List<TrackEntity> = emptyList()
    private var settings: MusicSettings = MusicSettings()
    private val recent = ArrayDeque<String>()
    private var targetBpm: Int = 0
    private var currentUri: String? = null
    private var fallbackIndex = 0
    private var ducked = false
    private var muted = false
    /**
     * Bumped by every fade. A step whose generation is stale does nothing, which
     * is what stops a track crossfade and a coaching duck from overwriting each
     * other — they used to collide at exactly the moment a cue was spoken.
     */
    private var fadeGeneration = 0

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    val hasMusic: Boolean get() = tracks.isNotEmpty()

    /**
     * Point the player at a playlist. Safe to call repeatedly during a session:
     * the engine re-reads the track table whenever it changes, so tracks added
     * on the Music tab are picked up without restarting the walk.
     */
    fun prepare(tracks: List<TrackEntity>, settings: MusicSettings) {
        this.tracks = tracks
        this.settings = settings
        if (player == null) {
            player = ExoPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
                .also { p ->
                    p.repeatMode = Player.REPEAT_MODE_OFF
                    p.volume = targetVolume()
                    p.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) nextTrack()
                        }
                    })
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

    private fun chooseNext(): TrackEntity? {
        if (tracks.isEmpty()) return null
        if (!settings.matchBpm) {
            val t = tracks[fallbackIndex % tracks.size]
            fallbackIndex++
            return t
        }
        val candidates = tracks.filter { it.bpm > 0.0 }.map { MatchCandidate(it.uri, it.bpm) }
        val match = TrackMatcher.pick(
            candidates = candidates,
            targetBpm = targetBpm,
            maxStretchPercent = settings.maxStretchPercent,
            allowOctave = settings.allowOctaveMatch,
            recentlyPlayed = recent.toList(),
        )
        if (match == null) {
            val t = tracks[fallbackIndex % tracks.size]
            fallbackIndex++
            return t
        }
        return tracks.firstOrNull { it.uri == match.id }
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

    private fun crossfadeTo(track: TrackEntity?) {
        val p = player ?: return
        if (track == null) return
        val start = {
            val (speed, match) = playbackFor(track)
            p.setMediaItem(MediaItem.fromUri(track.uri))
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
            )
            fadeTo(targetVolume(), 700)
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

    fun nextTrack() {
        crossfadeTo(chooseNext())
    }

    fun play() {
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
     * Hand the soundtrack over to another app, or take it back. The flag lives
     * here rather than in the service so that an interval change — which picks a
     * new track and starts it — cannot silently un-mute.
     */
    fun setMuted(value: Boolean) {
        muted = value
        if (value) {
            player?.pause()
        } else {
            player?.volume = targetVolume()
            player?.play()
        }
    }

    val isMuted: Boolean get() = muted

    fun pause() {
        player?.pause()
    }

    /** Lower the music while the voice coach talks. */
    fun duck(enable: Boolean) {
        if (ducked == enable) return
        ducked = enable
        // Drop fast so the first syllable is already audible; come back gently.
        fadeTo(targetVolume(), if (enable) 120 else 400)
    }

    fun setVolumePercent(percent: Int) {
        settings = settings.copy(volumePercent = percent.coerceIn(0, 100))
        fadeTo(targetVolume(), 150)
    }

    private fun baseVolume(): Float = settings.volumePercent.coerceIn(0, 100) / 100f

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

    private fun rememberRecent(uri: String) {
        recent.remove(uri)
        recent.addFirst(uri)
        while (recent.size > 8) recent.removeLast()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        _nowPlaying.value = null
    }
}
