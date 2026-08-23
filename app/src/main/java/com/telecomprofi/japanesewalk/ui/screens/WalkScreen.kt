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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.ui.components.Dot
import com.telecomprofi.japanesewalk.ui.components.HrRings
import com.telecomprofi.japanesewalk.ui.components.StatTile
import com.telecomprofi.japanesewalk.ui.components.ZoneThermometer
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.theme.color
import com.telecomprofi.japanesewalk.ui.vm.WalkViewModel
import com.telecomprofi.japanesewalk.workout.EngineStatus
import com.telecomprofi.japanesewalk.workout.WorkoutHub
import com.telecomprofi.japanesewalk.workout.WorkoutService

/**
 * The zones screen. Deliberately a single fixed page with no scrolling: mid-walk
 * you are looking at a phone at arm's length, possibly in the rain, and Finish
 * must never be one gesture away. Weekly and monthly figures live on Summary.
 *
 * @param onFinished called once the session ends, so the app can drop back to
 *        the Summary screen with fresh totals.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WalkScreen(onFinished: () -> Unit = {}, vm: WalkViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.workout.collectAsState()
    val bands by vm.bands.collectAsState()
    val plannedMinutes by vm.plannedMinutes.collectAsState()
    val info by vm.info.collectAsState()
    val musicMuted by vm.musicMuted.collectAsState()

    // Hand control back to Summary as soon as the walk is over — but only on a
    // real transition. Landing on this tab while a stale FINISHED state is still
    // published must not bounce the user straight back out.
    var sawActiveSession by remember { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        when (state.status) {
            EngineStatus.RUNNING, EngineStatus.PAUSED -> sawActiveSession = true
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

    val phaseColor = when (state.phase) {
        Phase.FAST -> JwColors.Fast
        Phase.SLOW -> JwColors.Slow
        Phase.WARMUP, Phase.COOLDOWN -> JwColors.Accent
        null -> JwColors.Muted
    }
    val active = state.status == EngineStatus.RUNNING || state.status == EngineStatus.PAUSED

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

        // The rings take whatever vertical space is left over once the numbers
        // and controls have had theirs — so they shrink, never the buttons.
        // The rings are the only flexible child: they absorb whatever is left
        // after the numbers and the controls, so the buttons can never be pushed
        // off the bottom of the screen.
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
                    phaseText = phaseText(state.phase, state.setNumber, state.totalSets),
                    phaseColor = phaseColor,
                    countdown = TimeMath.mmss(state.segmentRemainingSec),
                    idleHint = if (active) null else "$plannedMinutes min",
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = JwColors.Outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))

        ZoneThermometer(activeZone = state.zone)

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = JwColors.Outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatTile("elapsed", TimeMath.mmss(state.elapsedSec), Modifier.weight(1f), dense = true)
            StatTile("left", TimeMath.mmss(state.remainingSec), Modifier.weight(1f), dense = true)
            StatTile("kcal", state.kcal.toString(), Modifier.weight(1f), JwColors.Fast, dense = true)
            StatTile(
                "avg hr",
                if (state.avgHr > 0) state.avgHr.toString() else "--",
                Modifier.weight(1f),
                dense = true,
            )
        }

        val np = state.nowPlaying
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StrapLine(state.hrmState, state.strapStale, Modifier.weight(1f))
            if (active && np != null) {
                OutlinedButton(
                    onClick = { WorkoutService.send(context, WorkoutService.ACTION_TOGGLE_MUSIC) },
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text(if (musicMuted) "Unmute" else "Mute") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { WorkoutService.send(context, WorkoutService.ACTION_NEXT_TRACK) },
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("Skip track") }
            }
        }

        // One scrolling line rather than a four-row card.
        if (np != null) {
            Text(
                buildString {
                    append(np.title)
                    np.artist?.let { append(" — $it") }
                    append("   ·   ${np.trackBpm.toInt()} bpm")
                    if (np.multiplier != 1.0) append(" x${np.multiplier}")
                    append(" → ${np.effectiveBpm.toInt()} bpm")
                    if (np.stretchPercent != 0) {
                        append(" (${if (np.stretchPercent > 0) "+" else ""}${np.stretchPercent}%)")
                    }
                    append("   ·   target ${np.targetBpm} spm")
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (musicMuted) JwColors.Muted else phaseColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .basicMarquee(),
            )
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = JwColors.Outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(8.dp))

        if (active) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        } else {
            Button(
                onClick = { requestStart() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JwColors.Slow),
            ) {
                Text(
                    "Start $plannedMinutes-min IWT walk",
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

private fun phaseText(phase: Phase?, setNumber: Int, totalSets: Int): String = when (phase) {
    Phase.FAST -> "FAST · $setNumber/$totalSets"
    Phase.SLOW -> "SLOW · $setNumber/$totalSets"
    Phase.WARMUP -> "WARM-UP"
    Phase.COOLDOWN -> "COOL-DOWN"
    null -> "READY"
}
