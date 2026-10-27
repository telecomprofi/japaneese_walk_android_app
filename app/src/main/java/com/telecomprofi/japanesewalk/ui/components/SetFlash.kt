package com.telecomprofi.japanesewalk.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The set number, announced on the screen the way the coach announces it in
 * your ear: it arrives, it is unmissable for a moment, and then it gets out of
 * the way.
 *
 * Three movements. It springs up from nothing with a little overshoot — the
 * part that catches the eye in peripheral vision, which is all you have while
 * walking. It holds long enough to be read. Then it swells and dissolves at the
 * same time, so it leaves by becoming part of the background rather than by
 * being switched off; a thing that simply vanishes reads as a glitch, while a
 * thing that expands as it fades reads as deliberate.
 *
 * Nothing underneath it moves, and it never intercepts a touch.
 */
@Composable
fun SetFlash(
    /** Changing this is what triggers the flash; anything will do. */
    key: Any?,
    text: String,
    caption: String?,
    colour: Color,
    modifier: Modifier = Modifier,
) {
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }
    // Arriving on the screen mid-set should not set it off; only a change does.
    var seen by remember { mutableStateOf<Any?>(key) }

    LaunchedEffect(key) {
        if (key == null || key == seen) return@LaunchedEffect
        seen = key
        alpha.snapTo(0f)
        scale.snapTo(0.35f)
        launch { alpha.animateTo(1f, tween(130, easing = LinearOutSlowInEasing)) }
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        )
        delay(HOLD_MS)
        launch { scale.animateTo(2.3f, tween(DISSOLVE_MS, easing = FastOutLinearInEasing)) }
        alpha.animateTo(0f, tween(DISSOLVE_MS, easing = FastOutLinearInEasing))
    }

    if (alpha.value <= 0.01f) return

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
        ) {
            Text(
                text,
                fontSize = 116.sp,
                fontWeight = FontWeight.Black,
                color = colour,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (!caption.isNullOrBlank()) {
                Text(
                    caption,
                    style = MaterialTheme.typography.titleMedium,
                    color = colour.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Long enough to read a two-character number, short enough not to be in the way. */
private const val HOLD_MS = 420L
private const val DISSOLVE_MS = 620
