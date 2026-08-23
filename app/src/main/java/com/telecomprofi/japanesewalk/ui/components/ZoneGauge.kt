package com.telecomprofi.japanesewalk.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.telecomprofi.japanesewalk.core.GaugeGradient
import com.telecomprofi.japanesewalk.core.ZoneBand
import kotlin.math.min

/**
 * Angular width of each slice of the gradient fill. Small enough that the ramp
 * looks continuous, large enough that a frame is a few dozen draw calls rather
 * than a few hundred.
 */
private const val SLICE_DEG = 2f

/**
 * Draws a heart-rate gauge as a proportional fill.
 *
 * The fill runs from the start of the gauge to wherever the current heart rate
 * sits, so every zone below the current one is fully coloured and the current
 * zone is part-filled — the remainder stays grey because it has not been
 * reached. Colour comes from [GaugeGradient], which is also what the Data
 * screen's arc uses, so the two cannot disagree.
 *
 * Shared by the 270-degree ring and the 180-degree arc: same maths, different
 * start angle and sweep.
 */
internal fun DrawScope.drawZoneGauge(
    /** Centre of the circle the arc belongs to; not necessarily the canvas centre. */
    center: Offset,
    radius: Float,
    strokeWidth: Float,
    startAngle: Float,
    totalSweep: Float,
    fillFraction: Float,
    bands: List<ZoneBand>,
    trackAlpha: Float = 0.10f,
) {
    if (radius <= 0f || totalSweep <= 0f) return

    // Unreached remainder, drawn full length so the gauge always reads as a whole.
    arc(center, radius, strokeWidth, startAngle, totalSweep, Color.White.copy(alpha = trackAlpha), StrokeCap.Round)

    val fillSweep = totalSweep * fillFraction.coerceIn(0f, 1f)
    if (fillSweep <= 0.5f || bands.isEmpty()) return

    var offset = 0f
    while (offset < fillSweep) {
        val sweep = min(SLICE_DEG, fillSweep - offset)
        val midPosition = ((offset + sweep / 2f) / totalSweep).toDouble()
        val colour = Color(GaugeGradient.argbAt(midPosition, bands))
        val isEnd = offset <= 0f || offset + sweep >= fillSweep
        arc(
            center = center,
            radius = radius,
            strokeWidth = strokeWidth,
            startAngle = startAngle + offset,
            // Overlap slightly: butt-joined slices otherwise leave hairline seams.
            sweepAngle = sweep + if (isEnd) 0f else 0.7f,
            color = colour,
            cap = if (isEnd) StrokeCap.Round else StrokeCap.Butt,
        )
        offset += sweep
    }
}

private fun DrawScope.arc(
    center: Offset,
    radius: Float,
    strokeWidth: Float,
    startAngle: Float,
    sweepAngle: Float,
    color: Color,
    cap: StrokeCap,
) {
    if (sweepAngle <= 0f) return
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = strokeWidth, cap = cap),
    )
}
