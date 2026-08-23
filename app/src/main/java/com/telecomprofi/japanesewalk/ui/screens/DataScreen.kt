package com.telecomprofi.japanesewalk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telecomprofi.japanesewalk.core.DataMetric
import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.Pace
import com.telecomprofi.japanesewalk.core.TimeMath
import com.telecomprofi.japanesewalk.ui.components.ZoneArc
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.theme.color
import com.telecomprofi.japanesewalk.ui.vm.DataViewModel
import com.telecomprofi.japanesewalk.workout.WorkoutUiState

/**
 * The numbers screen: a gradient effort arc, then a grid of tiles. Which tiles
 * appear is up to the user — the sliders icon turns the grid into a picker.
 */
@Composable
fun DataScreen(vm: DataViewModel = viewModel()) {
    val state by vm.workout.collectAsState()
    val profile by vm.profile.collectAsState()
    val metrics by vm.metrics.collectAsState()
    val bands by vm.bands.collectAsState()
    var editing by remember { mutableStateOf(false) }

    val fraction = state.hr?.let { HrZone.gaugeFraction(it, profile).toFloat() } ?: 0f
    val zone = state.zone

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (editing) "Choose tiles" else "Data",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (!state.gpsTracking) {
                Text(
                    "no GPS",
                    style = MaterialTheme.typography.labelSmall,
                    color = JwColors.Muted,
                )
            }
            IconButton(onClick = { editing = !editing }) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = "Choose tiles",
                    tint = if (editing) JwColors.Slow else JwColors.Muted,
                )
            }
        }

        ZoneArc(bands = bands, fraction = fraction, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.hr?.toString() ?: "--",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 62.sp),
                    color = zone?.color() ?: JwColors.Muted,
                )
                Text(
                    zone?.label?.uppercase() ?: "BPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = zone?.color() ?: JwColors.Muted,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (editing) {
            DataMetric.entries.forEach { metric ->
                val on = metric in metrics
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) JwColors.SurfaceHigh else JwColors.Surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(metric.label, style = MaterialTheme.typography.bodyMedium)
                        if (metric.needsGps) {
                            Text(
                                "needs GPS",
                                style = MaterialTheme.typography.labelSmall,
                                color = JwColors.Muted,
                            )
                        }
                    }
                    IconButton(onClick = { vm.toggle(metric) }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = if (on) "Hide" else "Show",
                            tint = if (on) JwColors.Slow else JwColors.Outline,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        } else {
            // Three to a row, so a tile stays square-ish on a phone.
            metrics.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { metric ->
                        MetricCard(metric, state, Modifier.weight(1f))
                    }
                    // Keep the last row's tiles the same width as a full row's.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(metric: DataMetric, state: WorkoutUiState, modifier: Modifier = Modifier) {
    val (value, accent) = metric.valueOf(state)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(JwColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
            )
            if (metric.unit.isNotEmpty()) {
                Text(metric.unit, style = MaterialTheme.typography.labelSmall, color = JwColors.Muted)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                metric.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = JwColors.Muted,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** The one place a metric turns into something printable. */
private fun DataMetric.valueOf(state: WorkoutUiState): Pair<String, androidx.compose.ui.graphics.Color> {
    // Without a fix, a zero would read as "you have not moved" rather than
    // "nothing is being measured".
    if (needsGps && !state.gpsTracking) return "--" to JwColors.Muted
    return when (this) {
        DataMetric.BPM -> (state.hr?.toString() ?: "--") to JwColors.OnSurface
        DataMetric.DURATION -> TimeMath.mmss(state.elapsedSec) to JwColors.OnSurface
        DataMetric.DISTANCE -> Pace.formatKm(state.distanceMeters) to JwColors.Accent
        DataMetric.SPEED -> Pace.formatKph(state.currentSpeedMps) to JwColors.Accent
        DataMetric.AVG_SPEED -> Pace.formatKph(state.avgSpeedMps) to JwColors.Accent
        DataMetric.AVG_PACE ->
            Pace.formatPace(Pace.minutesPerKm(state.avgSpeedMps)) to JwColors.Accent
        DataMetric.AVG_BPM -> (if (state.avgHr > 0) state.avgHr.toString() else "--") to JwColors.OnSurface
        DataMetric.CALORIES -> state.kcal.toString() to JwColors.Fast
        DataMetric.KCAL_PER_MIN ->
            "%.1f".format(Pace.kcalPerMinute(state.kcal, state.elapsedSec)) to JwColors.Fast
    }
}
