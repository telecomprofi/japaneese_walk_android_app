package com.telecomprofi.japanesewalk.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.ui.components.KeyValueRow
import com.telecomprofi.japanesewalk.ui.components.NumberStepper
import com.telecomprofi.japanesewalk.ui.components.SectionCard
import com.telecomprofi.japanesewalk.ui.components.ToggleRow
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.theme.color
import com.telecomprofi.japanesewalk.ui.vm.SettingsViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsState()
    val devices by vm.devices.collectAsState()
    val hrmState by vm.hrmState.collectAsState()

    val profile = settings.profile
    val protocol = settings.protocol
    val plan = IntervalPlan(protocol)

    var showHelp by remember { mutableStateOf(false) }
    // Every explanatory line on this screen is optional; nothing is shown unless
    // the (i) is on, which keeps a settings page from reading like a manual.
    fun hint(text: String): String? = if (showHelp) text else null

    val btPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) vm.startScan()
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showHelp = !showHelp }) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "Show explanations",
                        tint = if (showHelp) JwColors.Accent else JwColors.Muted,
                    )
                }
            }
            SectionCard(title = "Heart-rate strap") {
                val label = when (val s = hrmState) {
                    is HrmState.Connected -> "Connected"
                    is HrmState.Scanning -> "Scanning…"
                    is HrmState.Failed -> s.message
                    else -> settings.hrm.deviceName ?: "None paired"
                }
                KeyValueRow("Status", label)
                settings.hrm.deviceAddress?.let { KeyValueRow("Paired", settings.hrm.deviceName ?: it) }
                if (settings.hrm.simulate) {
                    Text(
                        "Simulated heart rate is on in Debug — the strap is bypassed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JwColors.Gold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        btPermissions.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                            )
                        )
                    }) { Text("Scan") }
                    OutlinedButton(onClick = { vm.stopScan() }) { Text("Stop") }
                    if (settings.hrm.deviceAddress != null) {
                        TextButton(onClick = { vm.forgetStrap() }) {
                            Text("Forget", color = JwColors.Danger)
                        }
                    }
                }
                if (devices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    devices.forEach { device ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name ?: "Unnamed strap", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${device.address} · ${device.rssi} dBm",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = JwColors.Muted,
                                )
                            }
                            OutlinedButton(onClick = { vm.pair(device) }) { Text("Use") }
                        }
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { vm.startScan(filterByService = false) }) {
                        Text("Nothing found? Scan all Bluetooth devices")
                    }
                }
            }
        }

        item {
            SectionCard(title = "You") {
                NumberStepper("Age", profile.ageYears, { v -> vm.updateProfile { it.copy(ageYears = v) } }, 12..99)
                NumberStepper(
                    "Resting heart rate", profile.restingHr,
                    { v -> vm.updateProfile { it.copy(restingHr = v) } }, 35..100, suffix = " bpm",
                )
                ToggleRow(
                    label = "I know my max heart rate",
                    description = hint("Otherwise estimated as 208 − 0.7 × age = ${profile.predictedMaxHr} bpm"),
                    checked = profile.useMeasuredMaxHr,
                    onChange = { v -> vm.updateProfile { it.copy(useMeasuredMaxHr = v) } },
                )
                if (profile.useMeasuredMaxHr) {
                    NumberStepper(
                        "Max heart rate", profile.measuredMaxHr,
                        { v -> vm.updateProfile { it.copy(measuredMaxHr = v) } }, 120..220, suffix = " bpm",
                    )
                }
                NumberStepper(
                    "Weight", profile.weightKg.roundToInt(),
                    { v -> vm.updateProfile { it.copy(weightKg = v.toDouble()) } }, 30..250, suffix = " kg",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sex (for the calorie formula)", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = profile.isMale,
                        onClick = { vm.updateProfile { it.copy(isMale = true) } },
                        label = { Text("Male") },
                    )
                    FilterChip(
                        selected = !profile.isMale,
                        onClick = { vm.updateProfile { it.copy(isMale = false) } },
                        label = { Text("Female") },
                    )
                }
            }
        }

        item {
            SectionCard(title = "Your zones (Karvonen, %HRR)") {
                KeyValueRow("Max HR used", "${profile.maxHr} bpm")
                KeyValueRow("Heart-rate reserve", "${profile.hrReserve} bpm")
                Spacer(Modifier.height(6.dp))
                HrZone.bands(profile).forEach { band -> ZoneRow(band) }
                Spacer(Modifier.height(8.dp))
                val fastBand = protocol.targetBand(Phase.FAST)
                val slowBand = protocol.targetBand(Phase.SLOW)
                KeyValueRow(
                    "Fast range",
                    "${fastBand.rangeText(profile)} bpm",
                    valueColor = JwColors.Fast,
                )
                KeyValueRow(
                    "Slow ceiling",
                    "under ${slowBand.highBpm(profile)} bpm",
                    valueColor = JwColors.Slow,
                )
                if (showHelp) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Anywhere inside the fast range counts as on target — the coach only " +
                            "speaks up below ${fastBand.lowBpm(profile)} or above " +
                            "${fastBand.highBpm(profile)}. On the slow bout a lower heart rate " +
                            "is the goal, so it is never flagged.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JwColors.Muted,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Protocol · ${plan.totalSec / 60} min") {
                NumberStepper("Sets", protocol.sets, { v -> vm.updateProtocol { it.copy(sets = v) } }, 1..12)
                NumberStepper(
                    "Fast bout", protocol.fastSec / 60,
                    { v -> vm.updateProtocol { it.copy(fastSec = v * 60) } }, 1..10, suffix = " min",
                )
                NumberStepper(
                    "Slow bout", protocol.slowSec / 60,
                    { v -> vm.updateProtocol { it.copy(slowSec = v * 60) } }, 1..10, suffix = " min",
                )
                NumberStepper(
                    "Fast target — top of range", (protocol.fastPct * 100).roundToInt(),
                    { v -> vm.updateProtocol { it.copy(fastPct = v / 100.0) } }, 45..90, suffix = "% HRR",
                )
                NumberStepper(
                    "Slow ceiling", (protocol.slowPct * 100).roundToInt(),
                    { v -> vm.updateProtocol { it.copy(slowPct = v / 100.0) } }, 20..60, suffix = "% HRR",
                )
                NumberStepper(
                    "Range width below the top", (protocol.bandWidthPct * 100).roundToInt(),
                    { v -> vm.updateProtocol { it.copy(bandWidthPct = v / 100.0) } },
                    5..40, step = 5, suffix = " points HRR",
                )
                NumberStepper(
                    "Warm-up", protocol.warmupSec / 60,
                    { v -> vm.updateProtocol { it.copy(warmupSec = v * 60) } }, 0..10, suffix = " min",
                )
                NumberStepper(
                    "Cool-down", protocol.cooldownSec / 60,
                    { v -> vm.updateProtocol { it.copy(cooldownSec = v * 60) } }, 0..10, suffix = " min",
                )
                ToggleRow(
                    label = "Start with the slow bout",
                    description = hint("The published protocol does; it doubles as a warm-up"),
                    checked = protocol.startWithSlow,
                    onChange = { v -> vm.updateProtocol { it.copy(startWithSlow = v) } },
                )
                NumberStepper(
                    "Fast cadence", protocol.fastCadenceSpm,
                    { v -> vm.updateProtocol { it.copy(fastCadenceSpm = v) } }, 90..160, suffix = " steps/min",
                )
                NumberStepper(
                    "Slow cadence", protocol.slowCadenceSpm,
                    { v -> vm.updateProtocol { it.copy(slowCadenceSpm = v) } }, 70..140, suffix = " steps/min",
                )
            }
        }

        item {
            SectionCard(title = "Voice coach") {
                ToggleRow(
                    label = "Spoken cues",
                    checked = settings.coach.voiceEnabled,
                    onChange = { v -> vm.updateCoach { it.copy(voiceEnabled = v) } },
                )
                ToggleRow(
                    label = "Tell me when I drift off target",
                    checked = settings.coach.announceZoneDrift,
                    onChange = { v -> vm.updateCoach { it.copy(announceZoneDrift = v) } },
                )
                ToggleRow(
                    label = "Halfway call-out",
                    checked = settings.coach.halfwayCue,
                    onChange = { v -> vm.updateCoach { it.copy(halfwayCue = v) } },
                )
                NumberStepper(
                    "Interval countdown", settings.coach.countdownSeconds,
                    { v -> vm.updateCoach { it.copy(countdownSeconds = v) } }, 0..30, step = 5, suffix = " s",
                )
                NumberStepper(
                    "Speech rate", (settings.coach.speechRate * 10).roundToInt(),
                    { v -> vm.updateCoach { it.copy(speechRate = v / 10f) } }, 6..16,
                    suffix = " (x0.1)",
                )
                NumberStepper(
                    "Quiet time between cues", settings.coach.minCueGapSec,
                    { v -> vm.updateCoach { it.copy(minCueGapSec = v) } },
                    10..120, step = 5, suffix = " s",
                )
                NumberStepper(
                    "Wait before correcting me", settings.coach.driftGraceSec,
                    { v -> vm.updateCoach { it.copy(driftGraceSec = v) } },
                    10..120, step = 5, suffix = " s",
                )
                NumberStepper(
                    "Silence at the start of a bout", settings.coach.boutSettleSec,
                    { v -> vm.updateCoach { it.copy(boutSettleSec = v) } },
                    0..120, step = 15, suffix = " s",
                )
                if (showHelp) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Interval changes and countdowns are always spoken on time. " +
                            "Everything else waits for the quiet window.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JwColors.Muted,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Weekly goal") {
                NumberStepper(
                    "Weekly target", settings.reminders.weeklyTargetMinutes,
                    { v -> vm.updateReminders { it.copy(weeklyTargetMinutes = v) } },
                    30..600, step = 10, suffix = " min",
                )
                ToggleRow(
                    label = "Daily reminder",
                    description = hint("A note on how the week is going"),
                    checked = settings.reminders.enabled,
                    onChange = { v -> vm.updateReminders { it.copy(enabled = v) } },
                )
                KeyValueRow(
                    "Reminder time",
                    "%02d:%02d".format(settings.reminders.hourOfDay, settings.reminders.minuteOfHour),
                    valueColor = JwColors.Accent,
                )
                NumberStepper(
                    "Hour", settings.reminders.hourOfDay,
                    { v -> vm.updateReminders { it.copy(hourOfDay = v) } }, 0..23, suffix = "h",
                )
                NumberStepper(
                    "Minutes past the hour", settings.reminders.minuteOfHour,
                    { v -> vm.updateReminders { it.copy(minuteOfHour = v) } },
                    0..55, step = 5, suffix = " min",
                )
            }
        }

        item {
            SectionCard(title = "Debug") {
                ToggleRow(
                    label = "Simulate heart rate",
                    description = "Feeds synthetic readings so the app can be tried " +
                        "indoors. While this is on the paired strap is not used at all.",
                    checked = settings.hrm.simulate,
                    onChange = { value -> vm.updateHrm { it.copy(simulate = value) } },
                )
                if (settings.hrm.simulate && settings.hrm.deviceAddress != null) {
                    Text(
                        "Strap \"${settings.hrm.deviceName ?: settings.hrm.deviceAddress}\" " +
                            "stays paired and will be used again as soon as this is off.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JwColors.Muted,
                    )
                }
            }
        }

        if (showHelp) item {
            SectionCard(title = "About") {
                Text(
                    "Interval Walking Training: alternate 3 minutes brisk with 3 minutes easy, " +
                        "five times over. Brisk aims for " +
                        "${protocol.targetBand(Phase.FAST).rangeText(profile)} bpm and easy stays under " +
                        "${protocol.targetBand(Phase.SLOW).highBpm(profile)} bpm. All data stays on this " +
                        "phone — no account, no upload, no sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JwColors.Muted,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** One zone in the Settings list, carrying its own colour so the ring, the
 *  thermometer and this table can never disagree about what Z4 looks like. */
@Composable
private fun ZoneRow(band: com.telecomprofi.japanesewalk.core.ZoneBand) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 14.dp, height = 22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(band.zone.color()),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${band.zone.shortLabel} · ${band.zone.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = band.zone.color(),
            )
            Text(
                "${(band.zone.lowerPct * 100).toInt()}–${(band.zone.upperPct * 100).toInt()}% HRR",
                style = MaterialTheme.typography.labelSmall,
                color = JwColors.Muted,
            )
        }
        Text(
            "${band.loBpm}–${band.hiBpm} bpm",
            style = MaterialTheme.typography.titleMedium,
            color = band.zone.color(),
        )
    }
}
