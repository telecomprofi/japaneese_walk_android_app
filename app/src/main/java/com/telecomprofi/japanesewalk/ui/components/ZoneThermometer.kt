package com.telecomprofi.japanesewalk.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.theme.color

/**
 * A thermometer for effort: one segment per heart-rate zone, lit in the zone's
 * own colour up to and including the one you are in, greyed out above it. The
 * live segment stands taller than the rest.
 *
 * Colour and height are animated so a zone change reads as a movement rather
 * than a flicker — which is what makes it legible at a glance while walking.
 */
@Composable
fun ZoneThermometer(
    activeZone: HrZone?,
    modifier: Modifier = Modifier,
    segmentHeight: Dp = 14.dp,
) {
    Row(
        modifier.fillMaxWidth().height(segmentHeight * 1.7f),
        // Butt-joined: no separators between zones.
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        HrZone.ordered.forEach { zone ->
            val lit = activeZone != null && zone.idx <= activeZone.idx
            val isCurrent = zone == activeZone

            val color by animateColorAsState(
                targetValue = if (lit) zone.color() else zone.color().copy(alpha = 0.15f),
                animationSpec = tween(420),
                label = "zone-colour-${zone.idx}",
            )
            val height by animateDpAsState(
                targetValue = if (isCurrent) segmentHeight * 1.7f else segmentHeight,
                animationSpec = tween(320),
                label = "zone-height-${zone.idx}",
            )

            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(RoundedCornerShape(if (isCurrent) 5.dp else 0.dp))
                    .background(color),
            )
        }
    }
}
