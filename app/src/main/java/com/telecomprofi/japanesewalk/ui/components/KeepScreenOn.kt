package com.telecomprofi.japanesewalk.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Hold the screen awake while [enabled].
 *
 * Scoped to a composable with a DisposableEffect rather than set once on the
 * activity, so the flag is dropped the moment the walk ends or the setting is
 * turned off — a keep-awake flag left on after a session is a flat battery.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (enabled) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
