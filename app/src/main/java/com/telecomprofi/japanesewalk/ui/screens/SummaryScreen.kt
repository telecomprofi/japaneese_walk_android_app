package com.telecomprofi.japanesewalk.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.material3.FilterChip
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
import com.telecomprofi.japanesewalk.BuildConfig
import com.telecomprofi.japanesewalk.core.TimeMath
import com.telecomprofi.japanesewalk.core.TrainingLoad
import com.telecomprofi.japanesewalk.core.TrainingStatus
import com.telecomprofi.japanesewalk.core.TrainingVerdict
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.data.db.SessionEntity
import com.telecomprofi.japanesewalk.data.repo.WeekTable
import com.telecomprofi.japanesewalk.ui.components.IconStat
import com.telecomprofi.japanesewalk.ui.components.KeyValueRow
import com.telecomprofi.japanesewalk.ui.components.SectionCard
import com.telecomprofi.japanesewalk.ui.components.SessionDotsRow
import com.telecomprofi.japanesewalk.ui.components.StatTile
import com.telecomprofi.japanesewalk.ui.components.ThinProgressBar
import com.telecomprofi.japanesewalk.ui.components.ToggleRow
import com.telecomprofi.japanesewalk.ui.components.ZoneBar
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.theme.color
import com.telecomprofi.japanesewalk.ui.vm.SummaryViewModel
import com.telecomprofi.japanesewalk.workout.WorkoutService
import kotlin.math.roundToInt

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
    val mode by vm.mode.collectAsState()
    val trainerName by vm.trainerName.collectAsState()
    val lastSession by vm.lastSession.collectAsState()
    val weekTable by vm.weekTable.collectAsState()
    val training by vm.training.collectAsState()
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

    // isActive covers the post-plan cool-down too. Without that the button read
    // "Start Workout" during cool-down and then did nothing, because the service
    // already has an engine.
    val sessionRunning = workout.isActive

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
                when {
                    sessionRunning && mode == WorkoutMode.CYCLE -> "Back to the ride"
                    sessionRunning -> "Back to the walk"
                    mode == WorkoutMode.CYCLE -> "Start ride · $plannedMinutes min"
                    else -> "Start walk · $plannedMinutes min"
                },
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(8.dp))
        // Same protocol either way — five sets of three minutes hard and three
        // easy, judged by heart rate. Only the lever differs.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkoutMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { vm.setMode(m) },
                    enabled = !sessionRunning,
                    label = { Text(m.label) },
                )
            }
        }
        if (mode == WorkoutMode.CYCLE) {
            Text(
                trainerName?.let { "Trainer: $it" }
                    ?: "No trainer paired — pair one in Settings to have the app set the resistance",
                style = MaterialTheme.typography.labelSmall,
                color = if (trainerName != null) JwColors.Muted else JwColors.Gold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(4.dp))
        if (mode == WorkoutMode.WALK) ToggleRow(
            label = "Indoor / treadmill",
            description = "No GPS: skips distance, speed and pace, and leaves the radio alone.",
            checked = indoorMode,
            onChange = { vm.setIndoorMode(it) },
        )

        lastSession?.let { session ->
            Spacer(Modifier.height(18.dp))
            LastWorkoutCard(session)
        }

        Spacer(Modifier.height(12.dp))
        TrainingCard(training)

        Spacer(Modifier.height(12.dp))
        WeekTableCard(weekTable)

        Spacer(Modifier.height(14.dp))
        Text(
            "Japanese Walk ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                if (BuildConfig.DEBUG) " · debug" else "",
            style = MaterialTheme.typography.labelSmall,
            color = JwColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}


/**
 * What the last session came to, in the six numbers worth having, each behind
 * the picture of what it is. Shown whether or not the session was long enough to
 * count towards the week — a walk that fell short is still a walk you did.
 */
@Composable
private fun LastWorkoutCard(session: SessionEntity) {
    val walk = session.workoutMode == WorkoutMode.WALK
    val zone = session.dominantZone
    SectionCard(
        title = "Last ${if (walk) "walk" else "ride"} · ${TimeMath.dayName(session.startedAt)} " +
            TimeMath.timeOfDay(session.startedAt)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconStat(
                icon = Icons.Filled.Schedule,
                label = "duration",
                value = TimeMath.humanMinutes(session.elapsedSec),
                modifier = Modifier.weight(1f),
            )
            IconStat(
                icon = Icons.Filled.LocalFireDepartment,
                label = "kcal",
                value = session.kcal.toString(),
                modifier = Modifier.weight(1f),
                accent = JwColors.Fast,
            )
            IconStat(
                icon = Icons.Filled.Repeat,
                label = "sets",
                value = "${session.completedSets}/${session.plannedSets}",
                modifier = Modifier.weight(1f),
                accent = if (session.completedSets >= session.plannedSets) JwColors.Slow
                    else JwColors.OnSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconStat(
                icon = Icons.Filled.Favorite,
                label = "avg hr",
                value = if (session.avgHr > 0) "${session.avgHr}" else "--",
                modifier = Modifier.weight(1f),
                accent = JwColors.Danger,
            )
            IconStat(
                icon = Icons.Filled.Whatshot,
                label = "max hr",
                value = if (session.maxHr > 0) "${session.maxHr}" else "--",
                modifier = Modifier.weight(1f),
                accent = JwColors.Gold,
            )
            IconStat(
                icon = if (walk) Icons.AutoMirrored.Filled.DirectionsWalk
                    else Icons.AutoMirrored.Filled.DirectionsBike,
                label = "on target",
                value = "${session.adherencePercent}%",
                modifier = Modifier.weight(1f),
                accent = JwColors.Accent,
            )
        }

        if (session.hasZoneData) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Most of it in ${zone.shortLabel} · ${zone.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = zone.color(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    session.protocolLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = JwColors.Muted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            ZoneBar(session.zoneSeconds)
        }
    }
}

/**
 * The week, day by day. Bars rather than a column of numbers: the useful
 * question on a Sunday morning is which days were empty, and a bar chart answers
 * it without being read.
 */
@Composable
private fun WeekTableCard(table: WeekTable) {
    SectionCard(
        title = if (table.achieved) "This week · target cleared"
            else "This week · ${table.minutes} of ${table.targetMinutes} min"
    ) {
        val busiest = table.busiestMinutes.coerceAtLeast(1)
        table.days.forEach { day ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    day.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day.isToday) JwColors.OnSurface else JwColors.Muted,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(38.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(JwColors.Track),
                ) {
                    if (day.minutes > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth(day.minutes.toFloat() / busiest)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (day.modes.any { it == WorkoutMode.CYCLE }) JwColors.Accent
                                    else JwColors.Slow
                                ),
                        )
                    }
                }
                Text(
                    if (day.minutes > 0) "${day.minutes}m" else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day.minutes > 0) JwColors.OnSurface else JwColors.Muted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
                Text(
                    if (day.kcal > 0) "${day.kcal}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = JwColors.Fast,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = JwColors.Muted.copy(alpha = 0.28f))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile("sessions", table.sessions.toString(), Modifier.weight(1f), compact = true)
            StatTile("active days", "${table.activeDays}/7", Modifier.weight(1f), compact = true)
            StatTile("kcal", table.kcal.toString(), Modifier.weight(1f), JwColors.Fast, compact = true)
            StatTile(
                "avg hr",
                if (table.avgHr > 0) table.avgHr.toString() else "--",
                Modifier.weight(1f),
                JwColors.Danger,
                compact = true,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "A look back at this lands as a notification on Sunday at 11am — " +
                "turn it off in Settings if you would rather not have it.",
            style = MaterialTheme.typography.labelSmall,
            color = JwColors.Muted,
        )
    }
}

/**
 * Fitness, fatigue, and what to do today.
 *
 * Two exponential averages of daily training impulse — six weeks for the
 * condition you have built, one week for the tiredness you are carrying — plus
 * the two things the protocol actually prescribes: four half-hour walks a week,
 * and at least two days off. The sentence matters more than the numbers, which
 * is why it is the biggest thing on the card.
 */
@Composable
private fun TrainingCard(status: TrainingStatus) {
    val accent = when (status.verdict) {
        TrainingVerdict.ON_TRACK -> JwColors.Slow
        TrainingVerdict.BUILDING -> JwColors.Accent
        TrainingVerdict.UNDER, TrainingVerdict.RESTING -> JwColors.Gold
        TrainingVerdict.TIRED, TrainingVerdict.NEEDS_REST -> JwColors.Fast
        TrainingVerdict.OVERREACHING -> JwColors.Danger
    }
    SectionCard(title = "Training") {
        Text(status.verdict.label, style = MaterialTheme.typography.titleMedium, color = accent)
        Spacer(Modifier.height(4.dp))
        Text(
            status.verdict.advice,
            style = MaterialTheme.typography.bodyMedium,
            color = JwColors.Muted,
        )

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconStat(
                icon = Icons.Filled.TrendingUp,
                label = "fitness",
                value = "${status.fitness.roundToInt()}",
                modifier = Modifier.weight(1f),
                accent = JwColors.Accent,
            )
            IconStat(
                icon = Icons.Filled.Bolt,
                label = "fatigue",
                value = "${status.fatigue.roundToInt()}",
                modifier = Modifier.weight(1f),
                accent = JwColors.Fast,
            )
            IconStat(
                icon = Icons.Filled.Spa,
                label = "freshness",
                value = buildString {
                    if (status.freshness > 0) append("+")
                    append(status.freshness.roundToInt())
                },
                modifier = Modifier.weight(1f),
                accent = if (status.freshness >= 0) JwColors.Slow else JwColors.Fast,
            )
        }

        Spacer(Modifier.height(12.dp))
        // The fortnight, one bar a day, so a run of days without a gap is
        // visible as exactly that.
        val peak = status.recent.maxOfOrNull { it.trimp }?.coerceAtLeast(1.0) ?: 1.0
        Row(
            Modifier.fillMaxWidth().height(34.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            status.recent.forEach { day ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(
                            if (day.trained) {
                                (day.trimp / peak).coerceIn(0.12, 1.0).toFloat()
                            } else {
                                0.06f
                            }
                        )
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (day.trained) accent else JwColors.Track),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        TrainingTargets(status)
    }
}

/** The two prescriptions, as plain counts. */
@Composable
private fun TrainingTargets(status: TrainingStatus) {
    KeyValueRow(
        "Sessions of 30 min this week",
        "${status.weeklySessions} of ${TrainingLoad.SESSIONS_TARGET}",
        valueColor = if (status.weeklySessions >= TrainingLoad.SESSIONS_TARGET) {
            JwColors.Slow
        } else {
            JwColors.Gold
        },
    )
    KeyValueRow(
        "Rest days this week",
        "${status.restDays} of ${TrainingLoad.REST_DAYS_TARGET} or more",
        valueColor = if (status.restDays >= TrainingLoad.REST_DAYS_TARGET) {
            JwColors.Slow
        } else {
            JwColors.Fast
        },
    )
    if (status.consecutiveDays > 1) {
        KeyValueRow(
            "Days in a row",
            "${status.consecutiveDays}",
            valueColor = if (status.consecutiveDays >= TrainingLoad.MAX_CONSECUTIVE_DAYS) {
                JwColors.Fast
            } else {
                JwColors.OnSurface
            },
        )
    }
    if (status.ratioTrusted) {
        KeyValueRow("This week against the last four", "%.2f×".format(status.loadRatio))
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "A rough steer, not a verdict: it knows your walks and nothing about " +
            "your sleep, your week, or the hill you chose.",
        style = MaterialTheme.typography.labelSmall,
        color = JwColors.Muted,
    )
}
