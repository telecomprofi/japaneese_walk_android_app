package com.telecomprofi.japanesewalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telecomprofi.japanesewalk.core.SessionCircle
import com.telecomprofi.japanesewalk.core.SessionDot
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.theme.color

/**
 * The name of a group of settings, with a rule under it. Cards on their own gave
 * a long settings page no shape — you could see eleven boxes and no order. This
 * is the cheapest thing that says "these three belong together".
 */
@Composable
fun GroupHeader(title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = JwColors.Accent,
            letterSpacing = 1.6.sp,
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(thickness = 1.dp, color = JwColors.Muted.copy(alpha = 0.28f))
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = JwColors.Surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = JwColors.Muted,
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/**
 * A number with the picture of what it measures over it. The icons are what make
 * a row of six figures readable at a glance on the way back from a walk — the
 * flame is calories before you have read the word.
 */
@Composable
fun IconStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = JwColors.OnSurface,
) {
    Column(modifier = modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            maxLines = 1,
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = JwColors.Muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * The three zones as one bar, in proportion to the time spent in each. A stacked
 * bar rather than three numbers because the shape of a good interval session is
 * recognisable: a green third, a yellow sliver, an orange half.
 */
@Composable
fun ZoneBar(
    secondsPerZone: List<Int>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val total = secondsPerZone.sum().coerceAtLeast(1)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(JwColors.Track),
    ) {
        com.telecomprofi.japanesewalk.core.HrZone.entries.forEachIndexed { index, zone ->
            val seconds = secondsPerZone.getOrElse(index) { 0 }
            if (seconds > 0) {
                Box(
                    Modifier
                        .weight(seconds.toFloat() / total)
                        .fillMaxHeight()
                        .background(zone.color()),
                )
            }
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = JwColors.OnSurface,
    sub: String? = null,
    compact: Boolean = false,
    /** Smaller still, for the numbers alongside the rings mid-walk. */
    dense: Boolean = false,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = when {
                dense -> MaterialTheme.typography.titleSmall
                compact -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.headlineMedium
            },
            color = accent,
            maxLines = 1,
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = JwColors.Muted,
            textAlign = TextAlign.Center,
        )
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.labelSmall, color = JwColors.Muted)
        }
    }
}

@Composable
fun ThinProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = JwColors.Slow,
    track: Color = JwColors.Track,
    height: androidx.compose.ui.unit.Dp = 8.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * The week as a row of circles: one per session the weekly target implies,
 * filled green with a tick once walked. Reads at a glance from across a room,
 * which a percentage never does.
 */
@Composable
fun SessionDotsRow(
    dots: List<SessionCircle>,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 34.dp,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dots.forEach { circle ->
            val dot = circle.state
            Box(
                Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(if (dot == SessionDot.DONE) JwColors.SlowSoft else Color.Transparent)
                    .border(
                        width = if (dot == SessionDot.PLANNED) 1.5.dp else 2.dp,
                        color = when (dot) {
                            SessionDot.DONE -> JwColors.SlowSoft
                            SessionDot.PARTIAL -> JwColors.Slow
                            SessionDot.PLANNED -> JwColors.Outline
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (dot == SessionDot.DONE) {
                    // A walker or a bicycle rather than a tick, so a week of
                    // three walks and two rides reads as such at a glance. Older
                    // sessions predate the distinction and show the walker.
                    Icon(
                        if (circle.mode == WorkoutMode.CYCLE) {
                            Icons.AutoMirrored.Filled.DirectionsBike
                        } else {
                            Icons.AutoMirrored.Filled.DirectionsWalk
                        },
                        contentDescription = if (circle.mode == WorkoutMode.CYCLE) {
                            "completed ride"
                        } else {
                            "completed walk"
                        },
                        tint = Color(0xFF0A2E16),
                        modifier = Modifier.size(diameter * 0.60f),
                    )
                }
            }
        }
    }
}

@Composable
fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = JwColors.OnSurface) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = JwColors.Muted)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
    }
}

/** Plus/minus stepper — friendlier than a keyboard for the numbers this app needs. */
@Composable
fun NumberStepper(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    step: Int = 1,
    suffix: String = "",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$value$suffix",
                style = MaterialTheme.typography.titleMedium,
                color = JwColors.Accent,
            )
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { onChange((value - step).coerceIn(range.first, range.last)) },
            enabled = value > range.first,
        ) { Text("–") }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.OutlinedButton(
            onClick = { onChange((value + step).coerceIn(range.first, range.last)) },
            enabled = value < range.last,
        ) { Text("+") }
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelSmall, color = JwColors.Muted)
            }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}
