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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
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
import com.telecomprofi.japanesewalk.core.SessionDot
import com.telecomprofi.japanesewalk.ui.theme.JwColors

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
    dots: List<SessionDot>,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 34.dp,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dots.forEach { dot ->
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
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "completed session",
                        tint = Color(0xFF0A2E16),
                        modifier = Modifier.size(diameter * 0.62f),
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
