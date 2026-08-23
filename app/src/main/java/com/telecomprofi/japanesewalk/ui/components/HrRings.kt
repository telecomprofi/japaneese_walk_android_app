package com.telecomprofi.japanesewalk.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.telecomprofi.japanesewalk.core.ZoneBand
import kotlin.math.min

/** The gauge occupies 270 degrees with the gap at the bottom, like a watch face. */
private const val START_ANGLE = 135f
private const val SWEEP = 270f

/**
 * A single thick 270-degree heart-rate gauge, filled in proportion to the
 * current heart rate.
 *
 * Every zone below the one you are in is fully coloured and the zone you are in
 * is part-filled: the grey remainder is the effort you have not reached. There
 * is no needle — the length of the fill says where you are, and its colour says
 * which zone that is.
 */
@Composable
fun HrRings(
    bands: List<ZoneBand>,
    gaugeFraction: Float,
    modifier: Modifier = Modifier,
    stroke: Dp = 34.dp,
    content: @Composable () -> Unit,
) {
    val animated by animateFloatAsState(
        targetValue = gaugeFraction.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "hr-gauge",
    )

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val width = stroke.toPx()
                drawZoneGauge(
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = (size.minDimension - width) / 2f,
                    strokeWidth = width,
                    startAngle = START_ANGLE,
                    totalSweep = SWEEP,
                    fillFraction = animated,
                    bands = bands,
                )
            }
            content()
        }
    }
}
