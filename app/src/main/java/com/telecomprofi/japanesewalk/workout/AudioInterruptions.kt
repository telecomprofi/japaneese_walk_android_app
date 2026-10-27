package com.telecomprofi.japanesewalk.workout

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "AudioInterruptions"

/**
 * Notices when the phone takes the audio away — in practice, an answered call.
 *
 * Implemented on the audio **mode** rather than on audio focus, deliberately.
 * Focus would have been the obvious choice, but this app requests focus itself
 * every time the voice coach speaks, so a focus listener hears its own app and
 * would pause the walk mid-cue. The mode is unambiguous: the telephony stack
 * (or a VoIP app) sets [AudioManager.MODE_IN_CALL] or
 * [AudioManager.MODE_IN_COMMUNICATION] for the duration of a call and nothing
 * else does.
 *
 * `addOnModeChangedListener` needs API 31; minSdk here is 33, so there is no
 * polling fallback to maintain.
 */
class AudioInterruptions(
    context: Context,
    private val onCallStarted: () -> Unit,
    private val onCallEnded: () -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Deliberately the main executor, not a direct one. The callback arrives on
     * a binder thread, and everything it touches — the engine's status, its
     * pause bookkeeping, the player — is otherwise only ever touched from the
     * main dispatcher. A direct executor let a mode change land in the middle
     * of a tick and corrupt the session clock.
     */
    private val executor = ContextCompat.getMainExecutor(context)
    private var inCall = false
    private var listener: AudioManager.OnModeChangedListener? = null

    private fun isCallMode(mode: Int): Boolean =
        mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE

    fun start() {
        val manager = audioManager ?: return
        val l = AudioManager.OnModeChangedListener { mode -> handle(isCallMode(mode)) }
        listener = l
        try {
            manager.addOnModeChangedListener(executor, l)
            // A call already in progress when the walk starts counts too.
            handle(isCallMode(manager.mode))
        } catch (e: Exception) {
            Log.w(TAG, "mode listener unavailable", e)
            listener = null
        }
    }

    private fun handle(nowInCall: Boolean) {
        if (nowInCall == inCall) return
        inCall = nowInCall
        // MODE_RINGTONE means it is only ringing: go quiet immediately rather
        // than talking over the ringtone, and pick up again if it is not answered.
        if (nowInCall) onCallStarted() else onCallEnded()
    }

    fun stop() {
        val manager = audioManager ?: return
        listener?.let {
            try {
                manager.removeOnModeChangedListener(it)
            } catch (e: Exception) {
                Log.w(TAG, "listener removal failed", e)
            }
        }
        listener = null
        inCall = false
    }
}
