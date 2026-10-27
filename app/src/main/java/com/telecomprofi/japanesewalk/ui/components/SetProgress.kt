package com.telecomprofi.japanesewalk.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.telecomprofi.japanesewalk.ui.theme.JwColors

/**
 * The session as a row of boxes: one box per set, each split into its slow and
 * fast bouts in proportion to their real lengths.
 *
 * It replaces the zone thermometer, which had become redundant — the gauge
 * already fills through the zone colours, so a second read-out of the same
 * number was wasted space. What was missing was progress through the *session*,
 * which is the thing you actually want to know at a glance: how many sets are
 * behind you and how far into this one you are.
 *
 * Painted means done. Slow bouts fill light green, fast bouts light orange, and
 * everything still to come sits at low alpha so the shape of the whole session
 * is visible from the first second.
 */
@Composable
fun SetProgress(
    sets: Int,
    slowSec: Int,
    fastSec: Int,
    startWithSlow: Boolean,
    elapsedSec: Int,
    modifier: Modifier = Modifier,
    /** Seconds of warm-up before the first set, so the maths lines up. */
    offsetSec: Int = 0,
    height: Dp = 16.dp,
) {
    val setSec = (slowSec + fastSec).coerceAtLeast(1)
    val activeSet = (((elapsedSec - offsetSec).coerceAtLeast(0)) / setSec) + 1

    Row(
        modifier.fillMaxWidth().height(height * 1.5f),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (set in 1..sets.coerceAtLeast(1)) {
            val setStart = offsetSec + (set - 1) * setSec
            val isCurrent = set == activeSet
            Row(
                Modifier
                    .weight(1f)
                    .height(if (isCurrent) height * 1.5f else height)
                    .clip(RoundedCornerShape(4.dp)),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                val bouts = if (startWithSlow) {
                    listOf(slowSec to false, fastSec to true)
                } else {
                    listOf(fastSec to true, slowSec to false)
                }
                var boutStart = setStart
                bouts.forEach { (dur, isFast) ->
                    if (dur > 0) {
                        BoutBar(
                            fraction = ((elapsedSec - boutStart).toFloat() / dur).coerceIn(0f, 1f),
                            fast = isFast,
                            modifier = Modifier.weight(dur.toFloat()),
                        )
                        boutStart += dur
                    }
                }
            }
        }
    }
}

@Composable
private fun BoutBar(fraction: Float, fast: Boolean, modifier: Modifier = Modifier) {
    val colour = if (fast) JwColors.FastSoft else JwColors.SlowSoft
    // Animated so a bout creeps across rather than stepping once a second.
    val filled by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(900),
        label = if (fast) "bout-fast" else "bout-slow",
    )
    Box(modifier.fillMaxHeight().background(colour.copy(alpha = 0.16f))) {
        if (filled > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(filled)
                    .background(colour),
            )
        }
    }
}
