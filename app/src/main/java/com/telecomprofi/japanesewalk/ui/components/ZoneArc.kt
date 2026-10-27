package com.telecomprofi.japanesewalk.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.telecomprofi.japanesewalk.core.ZoneBand

/**
 * The Data screen's effort arc: the same gauge as the Walk screen, opened out to
 * a half circle resting on its base. Same fill rule, same colour ramp, same zone
 * boundaries — only the geometry differs, so the two screens can never tell you
 * different things about the same heart rate.
 *
 * Its height is capped rather than derived from the width. A half circle as wide
 * as the phone is over 200dp tall, which pushed the track name and the music
 * buttons below the fold — and something you reach for mid-ride has to be on
 * screen without scrolling. [maxArcHeight] is what keeps it there.
 */
@Composable
fun ZoneArc(
    bands: List<ZoneBand>,
    fraction: Float,
    modifier: Modifier = Modifier,
    stroke: Dp = 20.dp,
    maxArcHeight: Dp = 132.dp,
    content: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "zone-arc",
    )

    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
        // Whichever is smaller: as wide as the screen, or as tall as allowed.
        val diameter = minOf(maxWidth, (maxArcHeight - stroke) * 2)
        Box(
            Modifier.width(diameter).height(diameter / 2 + stroke),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val width = stroke.toPx()
                drawZoneGauge(
                    // Centre on the bottom edge, so 180-360 degrees draws the
                    // upper half and nothing is clipped.
                    center = Offset(size.width / 2f, size.height - width / 2f),
                    radius = (size.width - width) / 2f,
                    strokeWidth = width,
                    startAngle = 180f,
                    totalSweep = 180f,
                    fillFraction = animated,
                    bands = bands,
                )
            }
            content()
        }
    }
}
