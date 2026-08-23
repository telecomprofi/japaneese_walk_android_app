package com.telecomprofi.japanesewalk.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telecomprofi.japanesewalk.ui.components.KeyValueRow
import com.telecomprofi.japanesewalk.ui.components.SectionCard
import com.telecomprofi.japanesewalk.ui.components.SessionDotsRow
import com.telecomprofi.japanesewalk.ui.components.StatTile
import com.telecomprofi.japanesewalk.ui.components.ThinProgressBar
import com.telecomprofi.japanesewalk.ui.components.ToggleRow
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.vm.SummaryViewModel
import com.telecomprofi.japanesewalk.workout.EngineStatus
import com.telecomprofi.japanesewalk.workout.WorkoutService

/**
 * The front door, kept deliberately sparse: the week as a row of circles, one
 * number that matters, and the button. Everything else is a tap away.
 */
@Composable
fun SummaryScreen(
    onStartWorkout: () -> Unit,
    onOpenWorkout: () -> Unit,
    vm: SummaryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val summary by vm.summary.collectAsState()
    val plannedMinutes by vm.plannedMinutes.collectAsState()
    val workout by vm.workout.collectAsState()
    val outcome by vm.outcome.collectAsState()
    val indoorMode by vm.indoorMode.collectAsState()
    var showMore by remember { mutableStateOf(false) }

    val week = summary.week

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        WorkoutService.start(context)
        onStartWorkout()
    }

    fun requestStart() {
        val needed = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            // Optional: refusing it only blanks the distance and speed tiles.
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            WorkoutService.start(context)
            onStartWorkout()
        } else {
            permissions.launch(needed.toTypedArray())
        }
    }

    val sessionRunning = workout.status == EngineStatus.RUNNING || workout.status == EngineStatus.PAUSED

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        Spacer(Modifier.height(18.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                "${week.minutes}",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 58.sp),
                color = if (week.achieved) JwColors.Slow else JwColors.OnSurface,
            )
            Text(
                " / ${week.targetMinutes} min",
                style = MaterialTheme.typography.titleMedium,
                color = JwColors.Muted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${week.percentOfTarget}%",
                style = MaterialTheme.typography.headlineMedium,
                color = if (week.achieved) JwColors.Slow else JwColors.Gold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        ThinProgressBar(
            fraction = if (week.targetMinutes == 0) 0f else week.minutes.toFloat() / week.targetMinutes,
            color = if (week.achieved) JwColors.Slow else JwColors.Gold,
            height = 10.dp,
        )

        Spacer(Modifier.height(18.dp))
        SessionDotsRow(summary.dots, Modifier.fillMaxWidth())

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile("today", "${summary.todayMinutes} min", Modifier.weight(1f), compact = true)
            StatTile("kcal", week.kcal.toString(), Modifier.weight(1f), JwColors.Fast, compact = true)
            StatTile("days left", week.daysLeft.toString(), Modifier.weight(1f), compact = true)
            StatTile(
                "walks",
                week.sessions.toString(),
                Modifier.weight(1f),
                JwColors.Accent,
                compact = true,
            )
        }

        outcome?.let { result ->
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (result.saved) {
                        "Walk saved · ${result.week.minutes} of ${result.week.targetMinutes} min this week"
                    } else {
                        "Last walk was too short to count"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.saved) JwColors.Slow else JwColors.Muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.dismissOutcome() }) {
                    Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { showMore = !showMore }) {
            Text(if (showMore) "Less" else "More", style = MaterialTheme.typography.labelSmall)
        }

        if (showMore) {
            SectionCard(title = summary.monthLabel.ifEmpty { "This month" }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile("minutes", summary.monthMinutes.toString(), Modifier.weight(1f), compact = true)
                    StatTile("kcal", summary.monthKcal.toString(), Modifier.weight(1f), JwColors.Fast, compact = true)
                    StatTile("walks", summary.monthSessions.toString(), Modifier.weight(1f), compact = true)
                    StatTile(
                        "avg hr",
                        if (summary.monthAvgHr > 0) summary.monthAvgHr.toString() else "--",
                        Modifier.weight(1f),
                        compact = true,
                    )
                }
                Spacer(Modifier.height(10.dp))
                KeyValueRow("Average per week", "${summary.monthMinutesPerWeek} min")
                KeyValueRow(
                    "Weeks on target",
                    "${summary.monthWeeksOnTarget} of ${summary.monthWeeksElapsed}",
                    valueColor = if (summary.monthWeeksElapsed > 0 &&
                        summary.monthWeeksOnTarget == summary.monthWeeksElapsed
                    ) JwColors.Slow else JwColors.OnSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { if (sessionRunning) onOpenWorkout() else requestStart() },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (sessionRunning) JwColors.Gold else JwColors.Slow,
            ),
        ) {
            Text(
                if (sessionRunning) "Back to walk" else "Start Workout · $plannedMinutes min",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(4.dp))
        ToggleRow(
            label = "Indoor / treadmill",
            description = "No GPS: skips distance, speed and pace, and leaves the radio alone.",
            checked = indoorMode,
            onChange = { vm.setIndoorMode(it) },
        )
    }
}
