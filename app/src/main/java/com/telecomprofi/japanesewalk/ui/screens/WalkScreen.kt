package com.telecomprofi.japanesewalk.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.TimeMath
import com.telecomprofi.japanesewalk.core.TrainerControlMode
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.ui.components.Dot
import com.telecomprofi.japanesewalk.ui.components.HrRings
import com.telecomprofi.japanesewalk.ui.components.MusicRow
import com.telecomprofi.japanesewalk.ui.components.SetFlash
import com.telecomprofi.japanesewalk.ui.components.SetLine
import com.telecomprofi.japanesewalk.ui.components.SetProgress
import com.telecomprofi.japanesewalk.ui.components.StatTile
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.theme.color
import com.telecomprofi.japanesewalk.ui.vm.WalkViewModel
import com.telecomprofi.japanesewalk.workout.EngineStatus
import com.telecomprofi.japanesewalk.workout.SessionShape
import com.telecomprofi.japanesewalk.workout.WorkoutHub
import com.telecomprofi.japanesewalk.workout.WorkoutService

/**
 * The walk screen. Deliberately a single fixed page with no scrolling: mid-walk
 * you are looking at a phone at arm's length, possibly in the rain, and Finish
 * must never be one gesture away. Weekly and monthly figures live on Summary.
 *
 * @param onFinished called once the session ends *and has been saved*, so the
 *        app can drop back to the Summary screen with fresh totals. Note that
 *        the cool-down counts as still running — the walk is not over until the
 *        heart rate has come down.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WalkScreen(onFinished: () -> Unit = {}, vm: WalkViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.workout.collectAsState()
    val settings by vm.settings.collectAsState()
    val idleBands by vm.bands.collectAsState()
    val plannedMinutes by vm.plannedMinutes.collectAsState()
    val info by vm.info.collectAsState()
    val musicMuted by vm.musicMuted.collectAsState()
    // While a session is running, everything geometric comes from the engine's
    // snapshot: changing Sets or the intensity model mid-walk must not redraw
    // the gauge or the set bar against a plan that is not being run.
    val bands = if (state.isActive && state.zoneBands.isNotEmpty()) state.zoneBands else idleBands
    val shape = if (state.isActive) {
        state.shape
    } else {
        SessionShape(
            sets = settings.protocol.sets,
            slowSec = settings.protocol.slowSec,
            fastSec = settings.protocol.fastSec,
            warmupSec = settings.protocol.warmupSec,
            startWithSlow = settings.protocol.startWithSlow,
        )
    }

    // Hand control back to Summary as soon as the walk is over — but only on a
    // real transition. Landing on this tab while a stale FINISHED state is still
    // published must not bounce the user straight back out.
    var sawActiveSession by remember { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        when (state.status) {
            EngineStatus.RUNNING, EngineStatus.PAUSED, EngineStatus.COOLING -> sawActiveSession = true
            EngineStatus.FINISHED -> if (sawActiveSession) {
                sawActiveSession = false
                onFinished()
            }
            else -> Unit
        }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { WorkoutService.start(context) }

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
        if (needed.isEmpty()) WorkoutService.start(context) else permissions.launch(needed.toTypedArray())
    }

    val phaseColor = when {
        state.isCooling -> JwColors.Accent
        state.phase == Phase.FAST -> JwColors.Fast
        state.phase == Phase.SLOW -> JwColors.Slow
        state.phase == null -> JwColors.Muted
        else -> JwColors.Accent
    }
    val active = state.isActive

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 8.dp),
    ) {
        info?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = JwColors.Gold,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .basicMarquee(),
            )
        }

        // The gauge is the only flexible child: it absorbs whatever is left
        // after the numbers and the controls, so the buttons can never be
        // pushed off the bottom of the screen.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            HrRings(
                bands = bands,
                gaugeFraction = state.gaugeFraction,
                modifier = Modifier.fillMaxHeight(),
            ) {
                RingCenter(
                    hr = state.hr,
                    zoneLabel = state.zone?.label,
                    zoneColor = state.zone?.color() ?: JwColors.Muted,
                    phaseText = when {
                        state.interrupted -> "CALL"
                        state.isCooling -> "COOL-DOWN"
                        else -> phaseText(state.mode, state.phase, state.setNumber, state.totalSets)
                    },
                    phaseColor = if (state.interrupted) JwColors.Gold else phaseColor,
                    countdown = if (state.isCooling) {
                        TimeMath.mmss(state.coolDownSec)
                    } else {
                        TimeMath.mmss(state.segmentRemainingSec)
                    },
                    idleHint = if (active) null else "$plannedMinutes min",
                )
            }

            // Over the gauge, not in the layout: the flash must not move
            // anything, and it must not swallow a tap on the dial behind it.
            // A local, because `state` is a delegated property and Kotlin will
            // not smart-cast its nullable phase through the delegate.
            val flashPhase = state.phase
            SetFlash(
                key = if (active) state.setNumber to flashPhase else null,
                text = when (flashPhase) {
                    Phase.WARMUP -> "WARM-UP"
                    Phase.COOLDOWN -> "COOL-DOWN"
                    else -> "${state.setNumber}"
                },
                caption = when (flashPhase) {
                    null, Phase.WARMUP, Phase.COOLDOWN -> null
                    else -> "${state.mode.label(flashPhase)} · set of ${state.totalSets}"
                },
                colour = phaseColor,
            )
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = JwColors.Outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(6.dp))

        SetLine(
            phase = state.phase,
            setNumber = state.setNumber,
            totalSets = if (state.isActive) state.totalSets else shape.sets,
            segmentElapsedSec = state.segmentElapsedSec,
            segmentTotalSec = state.segmentTotalSec,
            isCooling = state.isCooling,
            coolDownSec = state.coolDownSec,
            mode = if (state.isActive) state.mode else settings.mode,
        )
        Spacer(Modifier.height(5.dp))
        SetProgress(
            sets = shape.sets,
            slowSec = shape.slowSec,
            fastSec = shape.fastSec,
            startWithSlow = shape.startWithSlow,
            elapsedSec = state.planElapsedSec,
            offsetSec = shape.warmupSec,
        )

        // Only while riding: before the session starts there is no target and no
        // cadence, and three tiles of dashes are worse than none.
        if (active && state.mode == WorkoutMode.CYCLE) {
            Spacer(Modifier.height(6.dp))
            // What the trainer was told, what it is doing, and how fast you are
            // turning the cranks — the three numbers you actually steer by on a
            // bike, in the same place the walk screen keeps its pace figures.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val resistanceMode = state.controlMode == TrainerControlMode.RESISTANCE
                StatTile(
                    label = if (resistanceMode) "target res" else "target",
                    value = when {
                        !state.trainerControlling -> "manual"
                        resistanceMode -> state.trainerTarget.toString()
                        else -> "${state.trainerTarget} W"
                    },
                    modifier = Modifier.weight(1f),
                    accent = if (state.trainerControlling) JwColors.Gold else JwColors.Muted,
                    dense = true,
                )
                StatTile(
                    label = if (resistanceMode) "resistance" else "power",
                    value = when {
                        resistanceMode -> if (state.resistanceLevel > 0) {
                            state.resistanceLevel.toString()
                        } else {
                            "--"
                        }
                        state.powerW > 0 -> "${state.powerW} W"
                        else -> "--"
                    },
                    modifier = Modifier.weight(1f),
                    accent = JwColors.Gold,
                    dense = true,
                )
                StatTile(
                    label = "cadence rpm",
                    value = buildString {
                        append(if (state.cadenceRpm > 0) state.cadenceRpm.toString() else "--")
                        append(" / ")
                        append(state.targetCadenceSpm)
                    },
                    modifier = Modifier.weight(1f),
                    accent = JwColors.Accent,
                    dense = true,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = JwColors.Outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatTile("elapsed", TimeMath.mmss(state.elapsedSec), Modifier.weight(1f), dense = true)
            StatTile(
                if (state.isCooling) "extra" else "left",
                if (state.isCooling) TimeMath.mmss(state.coolDownSec) else TimeMath.mmss(state.remainingSec),
                Modifier.weight(1f),
                dense = true,
            )
            StatTile("kcal", state.kcal.toString(), Modifier.weight(1f), JwColors.Fast, dense = true)
            StatTile(
                "avg hr",
                if (state.avgHr > 0) state.avgHr.toString() else "--",
                Modifier.weight(1f),
                dense = true,
            )
        }

        // The tiles above say what the trainer is doing; this line only appears
        // when it is not doing it, because then there is something to act on.
        if (state.mode == WorkoutMode.CYCLE && active && !state.trainerControlling) {
            Text(
                if (state.trainerConnected) {
                    "Trainer connected but not accepting control — change resistance yourself"
                } else {
                    "No trainer — change resistance yourself"
                },
                style = MaterialTheme.typography.labelSmall,
                color = JwColors.Gold,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MusicRow(
            nowPlaying = state.nowPlaying,
            muted = musicMuted,
            status = state.musicStatus,
            controlsEnabled = active,
            accent = phaseColor,
            leading = { StrapLine(state.hrmState, state.strapStale, Modifier.fillMaxWidth()) },
        )

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = JwColors.Outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(8.dp))

        when {
            state.isCooling -> {
                Text(
                    "Plan done — keep walking gently. Still counting minutes and calories " +
                        "until your heart rate settles.",
                    style = MaterialTheme.typography.labelSmall,
                    color = JwColors.Muted,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                Button(
                    onClick = { WorkoutService.send(context, WorkoutService.ACTION_STOP) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JwColors.Accent),
                ) { Text("Finish and save", color = Color.White, fontWeight = FontWeight.Bold) }
            }

            active -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { WorkoutHub.togglePause() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JwColors.Gold),
                ) {
                    Text(
                        if (state.status == EngineStatus.RUNNING) "Pause" else "Resume",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = { WorkoutHub.skipSegment() },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                ) { Text("Skip") }
                Button(
                    onClick = { WorkoutService.send(context, WorkoutService.ACTION_STOP) },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JwColors.Danger),
                ) { Text("Finish", color = Color.White, fontWeight = FontWeight.Bold) }
            }

            else -> Button(
                onClick = { requestStart() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JwColors.Slow),
            ) {
                Text(
                    if (settings.mode == WorkoutMode.CYCLE) {
                        "Start $plannedMinutes-min interval ride"
                    } else {
                        "Start $plannedMinutes-min IWT walk"
                    },
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun RingCenter(
    hr: Int?,
    zoneLabel: String?,
    zoneColor: Color,
    phaseText: String,
    phaseColor: Color,
    countdown: String,
    idleHint: String?,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            hr?.toString() ?: "--",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 58.sp),
            color = if (hr == null) JwColors.Muted else zoneColor,
        )
        Text("BPM", style = MaterialTheme.typography.labelSmall, color = JwColors.Muted)
        if (zoneLabel != null) {
            Text(zoneLabel, style = MaterialTheme.typography.labelSmall, color = zoneColor)
        }
        if (idleHint == null) {
            Spacer(Modifier.height(6.dp))
            Text(phaseText, style = MaterialTheme.typography.labelSmall, color = phaseColor)
            Text(countdown, style = MaterialTheme.typography.titleMedium, color = JwColors.OnSurface)
        } else {
            Spacer(Modifier.height(6.dp))
            Text(idleHint, style = MaterialTheme.typography.labelSmall, color = JwColors.Muted)
        }
    }
}

@Composable
private fun StrapLine(state: HrmState, stale: Boolean, modifier: Modifier = Modifier) {
    val (label, color) = when {
        stale -> "Strap signal lost — searching" to JwColors.Gold
        state is HrmState.Connected ->
            ("Strap connected" + (state.batteryPercent?.let { " · $it%" } ?: "")) to JwColors.Slow
        state is HrmState.Simulated -> "Simulated heart rate" to JwColors.Accent
        state is HrmState.Connecting -> "Connecting to strap…" to JwColors.Gold
        state is HrmState.Scanning -> "Scanning…" to JwColors.Gold
        state is HrmState.Failed -> state.message to JwColors.Danger
        state is HrmState.Disconnected -> "Strap disconnected" to JwColors.Danger
        else -> "No strap" to JwColors.Muted
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(color, size = 7.dp)
        Text(
            "  $label",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

private fun phaseText(
    mode: WorkoutMode,
    phase: Phase?,
    setNumber: Int,
    totalSets: Int,
): String = when (phase) {
    Phase.FAST, Phase.SLOW -> "${mode.label(phase)} · $setNumber/$totalSets"
    Phase.WARMUP -> "WARM-UP"
    Phase.COOLDOWN -> "COOL-DOWN"
    null -> "READY"
}
