package com.telecomprofi.japanesewalk.workout

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.TargetVerdict
import java.util.Locale

private const val TAG = "VoiceCoach"

/**
 * Spoken coaching. Runs through the platform TTS engine with the
 * "navigation guidance" audio attributes, and asks the music player to duck
 * while it talks so cues are always audible over the playlist.
 */
class VoiceCoach(
    context: Context,
    /**
     * When the music is coming from another app there is no volume of ours to
     * lower, so ducking has to be requested through audio focus instead.
     */
    private val duckViaAudioFocus: Boolean = false,
    private val onSpeechActive: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private val focusLock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private var utteranceCounter = 0

    /** Touched from the TTS callback thread and from callers, hence atomic. */
    private val speaking = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = pickLanguage()
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (speaking.incrementAndGet() == 1) duckOthers(true)
                        onSpeechActive(true)
                    }

                    override fun onDone(utteranceId: String?) = finished()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished()

                    override fun onError(utteranceId: String?, errorCode: Int) = finished()

                    private fun finished() {
                        if (speaking.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
                            duckOthers(false)
                            onSpeechActive(false)
                        }
                    }
                })
                while (pending.isNotEmpty()) {
                    val (text, flush) = pending.removeFirst()
                    speakNow(text, flush)
                }
            } else {
                Log.w(TAG, "TTS unavailable (status $status)")
            }
        }
    }

    private fun pickLanguage(): Locale {
        val device = Locale.getDefault()
        val engine = tts ?: return Locale.US
        return if (engine.isLanguageAvailable(device) >= TextToSpeech.LANG_AVAILABLE) device else Locale.US
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * @param interrupt true for time-critical cues (interval changes), which
     *        should jump the queue rather than land three seconds late.
     */
    fun say(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) {
            pending += text to interrupt
            while (pending.size > 6) pending.removeFirst()
            return
        }
        speakNow(text, interrupt)
    }

    private fun speakNow(text: String, interrupt: Boolean) {
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        // Some engines default below full scale; say it explicitly so a cue is
        // never quieter than the music it has to carry over.
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, mode, params, "jw-${utteranceCounter++}")
    }

    /**
     * Ask other apps to turn down for the duration of a cue. TRANSIENT_MAY_DUCK
     * is the polite request: the other player lowers its volume rather than
     * stopping, which is what you want mid-walk.
     */
    private fun duckOthers(active: Boolean) = synchronized(focusLock) {
        if (!duckViaAudioFocus) return@synchronized
        val manager = audioManager ?: return@synchronized
        try {
            if (active) {
                if (focusRequest != null) return@synchronized
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                focusRequest?.let { manager.abandonAudioFocusRequest(it) }
                focusRequest = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio focus change failed", e)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        speaking.set(0)
        duckOthers(false)
        onSpeechActive(false)
    }

    fun shutdown() {
        duckOthers(false)
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown failed", e)
        }
        tts = null
        ready = false
    }
}

/** All spoken copy in one place, so tone stays consistent and is easy to tweak. */
object CoachScript {

    fun sessionStart(totalMinutes: Int, sets: Int): String =
        "Interval walking. $sets sets, $totalMinutes minutes. Start easy and let your heart rate settle."

    /**
     * Bouts are announced with the whole range, never a single number — the
     * point is a window to walk inside, not a figure to chase.
     */
    fun phaseChange(
        phase: Phase,
        setNumber: Int,
        totalSets: Int,
        lowBpm: Int,
        highBpm: Int,
    ): String = when (phase) {
        Phase.FAST ->
            "Fast interval. Set $setNumber of $totalSets. Aim for $lowBpm to $highBpm."
        Phase.SLOW ->
            "Ease off. Slow walk, set $setNumber of $totalSets. Keep it under $highBpm."
        Phase.WARMUP -> "Warming up. Easy pace, under $highBpm."
        Phase.COOLDOWN -> "Cool down. Nice and gentle now."
    }

    fun countdown(seconds: Int, next: Phase?): String {
        val what = when (next) {
            Phase.FAST -> "fast"
            Phase.SLOW -> "slow"
            Phase.COOLDOWN -> "cool down"
            else -> null
        }
        return if (what == null) "$seconds seconds left." else "$seconds seconds, then $what."
    }

    /**
     * Correction copy. On the slow bout only the ceiling is ever mentioned:
     * a low heart rate during recovery is the goal, so it is never flagged.
     */
    fun drift(verdict: TargetVerdict, phase: Phase, lowBpm: Int, highBpm: Int): String {
        val isRecovery = phase != Phase.FAST
        return when (verdict) {
            TargetVerdict.WAY_BELOW ->
                if (isRecovery) "" else "Under the band. Lift the pace — you want at least $lowBpm."
            TargetVerdict.BELOW ->
                if (isRecovery) "" else "Just under $lowBpm. A little more pace."
            TargetVerdict.ABOVE ->
                if (isRecovery) "Still above $highBpm. Slow down and let it drop."
                else "A touch over $highBpm. Settle back into the band."
            TargetVerdict.WAY_ABOVE ->
                if (isRecovery) "Heart rate is not coming down. Slow right down."
                else "Well above $highBpm. Ease off and let it come down."
            TargetVerdict.ON_TARGET -> ""
        }
    }

    fun halfway(): String = "Halfway. Same again."

    fun strapLost(): String = "Lost the heart rate strap. Checking again."

    fun strapBack(): String = "Heart rate back."

    fun finished(minutes: Int, kcal: Int, adherence: Int, weekMinutes: Int, weekTarget: Int): String {
        val week = if (weekMinutes >= weekTarget) {
            "That clears your $weekTarget minute week."
        } else {
            "${(weekTarget - weekMinutes).coerceAtLeast(0)} minutes left this week."
        }
        return "Session complete. $minutes minutes, $kcal calories, $adherence percent on target. $week"
    }
}
