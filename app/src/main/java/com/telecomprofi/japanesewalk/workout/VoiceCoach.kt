package com.telecomprofi.japanesewalk.workout

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.SpokenNumbers
import com.telecomprofi.japanesewalk.core.TargetVerdict
import com.telecomprofi.japanesewalk.core.WorkoutMode
import java.util.Locale

private const val TAG = "VoiceCoach"

/** How often the engine is asked whether it is still talking. */
private const val POLL_MS = 300L

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

    /**
     * While true, nothing is spoken and nothing is queued. Set during a phone
     * call: coaching over someone else's voice is worse than no coaching.
     */
    @Volatile
    private var suppressed = false

    /**
     * Whether the coach is talking — and how that is decided, which turns out to
     * matter more than anything else in this file.
     *
     * It used to be a count: up on onStart, down on onDone. That count can stick
     * above zero forever, because an utterance flushed from the queue by an
     * urgent cue (every interval change uses QUEUE_FLUSH) is often never
     * reported done at all. A stuck count meant the music was ducked to a tenth
     * of its volume for the rest of the walk — playing, title changing at every
     * interval, and inaudible. So the count is now only a fast path to duck
     * *quickly*, and the decision to un-duck belongs to a poll of the engine's
     * own [TextToSpeech.isSpeaking], which cannot get out of step with reality.
     */
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var quietPolls = 0

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
                    override fun onStart(utteranceId: String?) = setActive(true)

                    // Done and error only hurry the poll along; they are never
                    // trusted to end the duck on their own.
                    override fun onDone(utteranceId: String?) = pollSoon()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = pollSoon()

                    override fun onError(utteranceId: String?, errorCode: Int) = pollSoon()
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

    /**
     * The poll. Two quiet readings in a row — six hundred milliseconds of an
     * engine that says it is not speaking — and the music comes back, whatever
     * the callbacks did or did not report.
     */
    private val speechWatch = object : Runnable {
        override fun run() {
            val busy = try {
                tts?.isSpeaking == true
            } catch (e: Exception) {
                false
            }
            if (busy) {
                quietPolls = 0
            } else if (++quietPolls >= 2) {
                setActive(false)
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun setActive(value: Boolean) {
        handler.post {
            if (active == value) {
                if (value) armPoll()
                return@post
            }
            active = value
            quietPolls = 0
            duckOthers(value)
            onSpeechActive(value)
            if (value) armPoll() else handler.removeCallbacks(speechWatch)
        }
    }

    private fun armPoll() {
        handler.removeCallbacks(speechWatch)
        handler.postDelayed(speechWatch, POLL_MS)
    }

    private fun pollSoon() {
        handler.removeCallbacks(speechWatch)
        handler.postDelayed(speechWatch, 120)
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /** Go quiet, or start talking again. Suppressing also drops the backlog. */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        if (value) {
            pending.clear()
            stopSpeaking()
        }
    }

    /**
     * @param interrupt true for time-critical cues (interval changes), which
     *        should jump the queue rather than land three seconds late.
     */
    fun say(text: String, interrupt: Boolean = false) {
        if (text.isBlank() || suppressed) return
        if (!ready) {
            pending += text to interrupt
            while (pending.size > 6) pending.removeFirst()
            return
        }
        speakNow(text, interrupt)
    }

    private fun speakNow(text: String, interrupt: Boolean) {
        // Duck before the engine opens its mouth, not when it does: the first
        // word of an interval cue is the one worth hearing.
        setActive(true)
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
        setActive(false)
    }

    fun shutdown() {
        handler.removeCallbacks(speechWatch)
        // Torn down mid-sentence, the music would otherwise be left ducked —
        // which is audible during a cool-down that outlives the last cue.
        setActive(false)
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

    fun sessionStart(totalMinutes: Int, sets: Int, mode: WorkoutMode = WorkoutMode.WALK): String {
        val what = if (mode == WorkoutMode.CYCLE) "Interval ride" else "Interval walking"
        return "$what. ${SpokenNumbers.cardinal(sets)} sets, $totalMinutes minutes. " +
            "Start easy and let your heart rate settle."
    }

    /** Said when the trainer refuses control, so the rider knows to shift by hand. */
    fun trainerManual(): String =
        "No control of the trainer. Change the resistance yourself when I call the interval."

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
        mode: WorkoutMode = WorkoutMode.WALK,
        /** e.g. "130 watts" when the app is driving a trainer, else null. */
        targetText: String? = null,
        /** True when the rider has to change the resistance themselves. */
        manualResistance: Boolean = false,
    ): String {
        val target = targetText?.let { " $it." } ?: ""
        return when (phase) {
            Phase.FAST -> {
                val shift = if (manualResistance) " Resistance up." else ""
                "${mode.hardSpoken}. ${SpokenNumbers.ordinalSentence(setNumber)} set of " +
                    "${SpokenNumbers.cardinal(totalSets)}.$target$shift " +
                    "Aim for $lowBpm to $highBpm."
            }
            Phase.SLOW -> {
                val shift = if (manualResistance) " Resistance down." else ""
                "${mode.easySpoken}, ${SpokenNumbers.setOf(setNumber, totalSets)}.$target$shift " +
                    "Keep it under $highBpm."
            }
            Phase.WARMUP -> "Warming up. Easy pace, under $highBpm."
            Phase.COOLDOWN -> "Cool down. Nice and gentle now."
        }
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
    fun drift(
        verdict: TargetVerdict,
        phase: Phase,
        lowBpm: Int,
        highBpm: Int,
        mode: WorkoutMode = WorkoutMode.WALK,
    ): String {
        val isRecovery = phase != Phase.FAST
        val more = if (mode == WorkoutMode.CYCLE) "a little more effort" else "a little more pace"
        val lift = if (mode == WorkoutMode.CYCLE) "Push a bit harder" else "Lift the pace"
        return when (verdict) {
            TargetVerdict.WAY_BELOW ->
                if (isRecovery) "" else "Under the band. $lift — you want at least $lowBpm."
            TargetVerdict.BELOW ->
                if (isRecovery) "" else "Just under $lowBpm. ${more.replaceFirstChar { it.uppercase() }}."
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

    /**
     * The plan is done but the strap is still on. Said instead of the closing
     * summary, which now waits until the cool-down has actually finished.
     */
    fun planComplete(minutes: Int): String =
        "That is the $minutes minutes. Keep walking gently — still counting while your heart rate comes down."

    fun backFromCall(): String = "Back to it."

    fun strapLost(): String = "Lost the heart rate strap. Checking again."

    fun strapBack(): String = "Heart rate back."

    fun finished(
        minutes: Int,
        kcal: Int,
        adherence: Int,
        weekMinutes: Int,
        weekTarget: Int,
        coolDownMinutes: Int = 0,
    ): String {
        val week = if (weekMinutes >= weekTarget) {
            "That clears your $weekTarget minute week."
        } else {
            "${(weekTarget - weekMinutes).coerceAtLeast(0)} minutes left this week."
        }
        val cool = if (coolDownMinutes > 0) " Including $coolDownMinutes of cool-down." else ""
        return "Session complete. $minutes minutes, $kcal calories, " +
            "$adherence percent on target.$cool $week"
    }
}
