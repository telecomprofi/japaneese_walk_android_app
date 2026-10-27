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
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.IntensityModel
import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.MaxHrFormula
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.TrainerControlMode
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.trainer.TrainerState
import androidx.compose.ui.graphics.vector.ImageVector
import com.telecomprofi.japanesewalk.ui.components.GroupHeader
import com.telecomprofi.japanesewalk.ui.components.KeyValueRow
import com.telecomprofi.japanesewalk.ui.components.NumberStepper
import com.telecomprofi.japanesewalk.ui.components.SectionCard
import com.telecomprofi.japanesewalk.ui.components.ToggleRow
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.theme.color
import com.telecomprofi.japanesewalk.ui.vm.SettingsViewModel
import kotlin.math.roundToInt


/**
 * Settings used to be one long scroll with the trainer buried two thirds of the
 * way down. It is now three pages: what is true of you whatever you are doing,
 * then the two ways of doing it.
 */
private enum class SettingsPage(val label: String) {
    GENERAL("General"),
    WALKING("Walking"),
    CYCLING("Cycling"),
    ;

    fun icon(): ImageVector = when (this) {
        GENERAL -> Icons.Filled.Tune
        WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
        CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsState()
    val devices by vm.devices.collectAsState()
    val hrmState by vm.hrmState.collectAsState()
    val trainerDevices by vm.trainerDevices.collectAsState()
    val trainerState by vm.trainerState.collectAsState()
    val trainerResponse by vm.trainerResponse.collectAsState()
    val cycle = settings.cycle

    val profile = settings.profile
    val protocol = settings.protocol
    val model = profile.intensityModel
    val plan = IntervalPlan(protocol, model)

    var page by rememberSaveable { mutableStateOf(SettingsPage.GENERAL) }
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
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsPage.entries.forEach { p ->
                    FilterChip(
                        selected = page == p,
                        onClick = { page = p },
                        label = { Text(p.label) },
                        leadingIcon = {
                            Icon(p.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        when (page) {
            SettingsPage.GENERAL -> {
                item { GroupHeader("Your body") }
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
                SectionCard(title = "Your zones · ${model.label}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = model == IntensityModel.HR_RESERVE,
                            onClick = { vm.setIntensityModel(IntensityModel.HR_RESERVE) },
                            label = { Text("% reserve") },
                        )
                        FilterChip(
                            selected = model == IntensityModel.MAX_HR,
                            onClick = {
                                vm.setIntensityModel(IntensityModel.MAX_HR, MaxHrFormula.CLASSIC_220)
                            },
                            label = { Text("% of max (220 − age)") },
                        )
                    }
                    if (showHelp) {
                        Text(
                            model.blurb,
                            style = MaterialTheme.typography.labelSmall,
                            color = JwColors.Muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (model == IntensityModel.MAX_HR) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MaxHrFormula.entries.forEach { f ->
                                FilterChip(
                                    selected = profile.maxHrFormula == f && !profile.useMeasuredMaxHr,
                                    onClick = { vm.setIntensityModel(IntensityModel.MAX_HR, f) },
                                    label = { Text(f.label) },
                                )
                            }
                        }
                        if (profile.useMeasuredMaxHr) {
                            Text(
                                "Your entered maximum of ${profile.measuredMaxHr} bpm is being used " +
                                    "instead of the formula, so the zone boundaries below come from " +
                                    "that. Tap a formula above to use it instead.",
                                style = MaterialTheme.typography.labelSmall,
                                color = JwColors.Gold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("Max HR used", "${profile.maxHr} bpm")
                    if (model == IntensityModel.HR_RESERVE) {
                        KeyValueRow("Heart-rate reserve", "${profile.hrReserve} bpm")
                    }
                    Spacer(Modifier.height(6.dp))
                    HrZone.bands(profile).forEach { band -> ZoneRow(band, model) }
                    Spacer(Modifier.height(8.dp))
                    val fastBand = protocol.targetBand(Phase.FAST, model)
                    val slowBand = protocol.targetBand(Phase.SLOW, model)
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
                item { GroupHeader("Session plan") }
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
                    // The three intensity numbers are stored per model, so editing
                    // them here always edits the pair that is actually in use.
                    if (model == IntensityModel.HR_RESERVE) {
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
                    } else {
                        NumberStepper(
                            "Fast target — top of range", (protocol.fastMaxPct * 100).roundToInt(),
                            { v -> vm.updateProtocol { it.copy(fastMaxPct = v / 100.0) } },
                            65..95, suffix = "% HRmax",
                        )
                        NumberStepper(
                            "Slow ceiling", (protocol.slowMaxPct * 100).roundToInt(),
                            { v -> vm.updateProtocol { it.copy(slowMaxPct = v / 100.0) } },
                            45..75, suffix = "% HRmax",
                        )
                        NumberStepper(
                            "Range width below the top", (protocol.bandWidthMaxPct * 100).roundToInt(),
                            { v -> vm.updateProtocol { it.copy(bandWidthMaxPct = v / 100.0) } },
                            4..25, suffix = " points HRmax",
                        )
                    }
                    ToggleRow(
                        label = "Warm-up and cool-down",
                        description = hint(
                            "Five easy minutes before the first bout and five after the last, " +
                                "inside the plan and counted in the session. Set either to zero " +
                                "below to drop it."
                        ),
                        checked = protocol.warmupSec > 0 || protocol.cooldownSec > 0,
                        onChange = { on ->
                            vm.updateProtocol {
                                if (on) {
                                    it.copy(
                                        warmupSec = if (it.warmupSec > 0) it.warmupSec else 5 * 60,
                                        cooldownSec = if (it.cooldownSec > 0) it.cooldownSec else 5 * 60,
                                    )
                                } else {
                                    it.copy(warmupSec = 0, cooldownSec = 0)
                                }
                            }
                        },
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
                SectionCard(title = "After the last bout") {
                    ToggleRow(
                        label = "Keep counting the cool-down",
                        description = "Stays connected to the strap after the plan finishes and keeps " +
                            "crediting minutes and calories, as easy-paced walking, until your heart " +
                            "rate has settled back into zone 1.",
                        checked = settings.coolDown.trackAfterFinish,
                        onChange = { v -> vm.updateCoolDown { it.copy(trackAfterFinish = v) } },
                    )
                    if (settings.coolDown.trackAfterFinish) {
                        ToggleRow(
                            label = "Keep going while you are still walking",
                            description = hint(
                                "The plan ending is not the walk ending. While your heart rate " +
                                    "stays above the figure below, the strap stays connected, the " +
                                    "music keeps playing and the minutes keep counting — all of it " +
                                    "recorded as one session, not two."
                            ),
                            checked = settings.coolDown.extendWhileActive,
                            onChange = { v -> vm.updateCoolDown { it.copy(extendWhileActive = v) } },
                        )
                        if (settings.coolDown.extendWhileActive) {
                            NumberStepper(
                                "Still walking above", settings.coolDown.activeHrFloor,
                                { v -> vm.updateCoolDown { it.copy(activeHrFloor = v) } },
                                80..140, step = 5, suffix = " bpm",
                            )
                            NumberStepper(
                                "Stop extending after", settings.coolDown.maxExtraSec / 60,
                                { v -> vm.updateCoolDown { it.copy(maxExtraSec = v * 60) } },
                                10..90, step = 5, suffix = " min",
                            )
                        }
                        NumberStepper(
                            "Give up after", settings.coolDown.maxSec / 60,
                            { v -> vm.updateCoolDown { it.copy(maxSec = v * 60) } },
                            1..30, suffix = " min",
                        )
                        NumberStepper(
                            "Settled for", settings.coolDown.settledSec,
                            { v -> vm.updateCoolDown { it.copy(settledSec = v) } },
                            15..300, step = 15, suffix = " s",
                        )
                    }
                }
                }
                item { GroupHeader("Sensors") }
                item {
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
                item { GroupHeader("Coaching") }
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
                    Spacer(Modifier.height(6.dp))
                    ToggleRow(
                        label = "Sunday summary",
                        description = hint(
                            "The week just gone, day by day, at " +
                                "${settings.reminders.summaryHourOfDay}:00 on Sunday — while " +
                                "there is still a day left to do something about it. The same " +
                                "table is always on the Summary tab."
                        ),
                        checked = settings.reminders.weeklySummary,
                        onChange = { v -> vm.updateReminders { it.copy(weeklySummary = v) } },
                    )
                    if (settings.reminders.weeklySummary) {
                        NumberStepper(
                            "Summary hour", settings.reminders.summaryHourOfDay,
                            { v -> vm.updateReminders { it.copy(summaryHourOfDay = v) } },
                            6..21, suffix = "h",
                        )
                    }
                }
                }
                item { GroupHeader("Display") }
                item {
                SectionCard(title = "During a session") {
                    ToggleRow(
                        label = "Keep the screen on",
                        description = "Holds the display awake for the length of a session only. " +
                            "The session itself keeps running either way — this is just so you can " +
                            "glance at the gauge without waking the phone.",
                        checked = settings.display.keepScreenOn,
                        onChange = { v -> vm.updateDisplay { it.copy(keepScreenOn = v) } },
                    )
                    NumberStepper(
                        "Heart-rate transition", protocol.transitionSec,
                        { v -> vm.updateProtocol { it.copy(transitionSec = v) } },
                        0..120, step = 15, suffix = " s",
                    )
                    if (showHelp) {
                        Text(
                            "A heart does not step. Asked to go from easy to brisk it takes a minute " +
                                "or so to arrive, and as long to come back down, so the target range " +
                                "slides across that long instead of jumping — which is what stops the " +
                                "coach correcting a heart rate that is already on its way. 0 restores " +
                                "hard steps.",
                            style = MaterialTheme.typography.labelSmall,
                            color = JwColors.Muted,
                        )
                    }
                }
                }
                item { GroupHeader("Diagnostics") }
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
                            "${protocol.targetBand(Phase.FAST, model).rangeText(profile)} bpm and easy stays under " +
                            "${protocol.targetBand(Phase.SLOW, model).highBpm(profile)} bpm. All data stays on this " +
                            "phone — no account, no upload, no sync.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JwColors.Muted,
                    )
                }
                }
            }

            SettingsPage.WALKING -> {
                item { GroupHeader("Outdoors") }
                item {
                    SectionCard(title = "Where you walk") {
                        ToggleRow(
                            label = "Treadmill mode",
                            description = "No GPS at all. Distance, speed and pace mean nothing on " +
                                "a belt, and asking the radio for fixes indoors only costs battery. " +
                                "The same switch sits next to the Start button on the Summary tab.",
                            checked = settings.indoorMode,
                            onChange = { v -> vm.setIndoorMode(v) },
                        )
                        if (showHelp) {
                            Text(
                                "Outdoors the app records distance, average pace and a route-free " +
                                    "trace of speed from GPS. Everything else — heart rate, zones, " +
                                    "calories, the bouts themselves — works identically either way.",
                                style = MaterialTheme.typography.labelSmall,
                                color = JwColors.Muted,
                            )
                        }
                    }
                }
            }

            SettingsPage.CYCLING -> {
                item { GroupHeader("Trainer") }
                item {
                SectionCard(title = "Indoor trainer") {
                    val status = when (val t = trainerState) {
                        is TrainerState.Connected -> when {
                            t.hasControl -> "Connected, controlling"
                            else -> "Connected, no control"
                        }
                        is TrainerState.Scanning -> "Scanning…"
                        is TrainerState.Connecting -> "Connecting…"
                        is TrainerState.Failed -> t.message
                        is TrainerState.Disconnected -> "Disconnected"
                        else -> cycle.deviceName ?: "None paired"
                    }
                    KeyValueRow("Status", status)
                    cycle.deviceAddress?.let { KeyValueRow("Paired", cycle.deviceName ?: it) }
                    (trainerState as? TrainerState.Connected)?.let { t ->
                        t.powerRange?.let { KeyValueRow("Power range", "${it.min}–${it.max} W") }
                        t.resistanceRange?.let { KeyValueRow("Resistance range", "${it.min}–${it.max}") }
                        t.features?.let {
                            KeyValueRow(
                                "Can set",
                                listOfNotNull(
                                    "power".takeIf { _ -> it.canSetPower },
                                    "resistance".takeIf { _ -> it.canSetResistance },
                                ).joinToString(", ").ifEmpty { "nothing — coach only" },
                            )
                        }
                    }
                    trainerResponse?.let {
                        Text(
                            "Last command: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = JwColors.Muted,
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
                            vm.startTrainerScan()
                        }) { Text("Scan") }
                        OutlinedButton(onClick = { vm.stopTrainerScan() }) { Text("Stop") }
                        if (cycle.deviceAddress != null) {
                            TextButton(onClick = { vm.forgetTrainer() }) { Text("Forget") }
                        }
                    }
                    if (trainerDevices.isNotEmpty()) {
                        trainerDevices.forEach { device ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        device.name ?: device.address,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "${device.rssi} dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = JwColors.Muted,
                                    )
                                }
                                TextButton(onClick = { vm.pairTrainer(device) }) { Text("Use") }
                            }
                        }
                    } else if (showHelp) {
                        Text(
                            "Nothing listed? Some trainers only advertise the Fitness Machine service " +
                                "while they are awake — turn the cranks once and scan again.",
                            style = MaterialTheme.typography.labelSmall,
                            color = JwColors.Muted,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("How the app changes the effort", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrainerControlMode.entries.forEach { m ->
                            FilterChip(
                                selected = cycle.controlMode == m,
                                onClick = { vm.updateCycle { it.copy(controlMode = m) } },
                                label = { Text(m.label.substringBefore(" (")) },
                            )
                        }
                    }
                    if (showHelp) {
                        Text(
                            cycle.controlMode.blurb,
                            style = MaterialTheme.typography.labelSmall,
                            color = JwColors.Muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    when (cycle.controlMode) {
                        TrainerControlMode.POWER -> {
                            NumberStepper(
                                "Easy bout", cycle.easyWatts,
                                { v -> vm.updateCycle { it.copy(easyWatts = v) } },
                                30..200, step = 5, suffix = " W",
                            )
                            NumberStepper(
                                "Hard bout", cycle.hardWatts,
                                { v -> vm.updateCycle { it.copy(hardWatts = v) } },
                                50..400, step = 5, suffix = " W",
                            )
                        }
                        TrainerControlMode.RESISTANCE -> {
                            NumberStepper(
                                "Easy bout", cycle.easyResistance,
                                { v -> vm.updateCycle { it.copy(easyResistance = v) } },
                                0..100, step = 5, suffix = "%",
                            )
                            NumberStepper(
                                "Hard bout", cycle.hardResistance,
                                { v -> vm.updateCycle { it.copy(hardResistance = v) } },
                                0..100, step = 5, suffix = "%",
                            )
                        }
                        TrainerControlMode.NONE -> Unit
                    }
                    NumberStepper(
                        "Easy cadence", cycle.easyCadenceRpm,
                        { v -> vm.updateCycle { it.copy(easyCadenceRpm = v) } },
                        50..110, suffix = " rpm",
                    )
                    NumberStepper(
                        "Hard cadence", cycle.hardCadenceRpm,
                        { v -> vm.updateCycle { it.copy(hardCadenceRpm = v) } },
                        60..120, suffix = " rpm",
                    )

                    if (cycle.controlMode == TrainerControlMode.POWER) {
                        ToggleRow(
                            label = "Follow my heart rate",
                            description = "Creeps the watts up or down between bouts until your heart " +
                                "rate sits in the prescribed band. The bouts are written in heart rate, " +
                                "and how many watts that takes is different for everyone — and " +
                                "different again on a hot day.",
                            checked = cycle.autoAdjust,
                            onChange = { v -> vm.updateCycle { it.copy(autoAdjust = v) } },
                        )
                        if (cycle.autoAdjust) {
                            NumberStepper(
                                "Step", cycle.adjustStepWatts,
                                { v -> vm.updateCycle { it.copy(adjustStepWatts = v) } },
                                1..25, suffix = " W",
                            )
                            NumberStepper(
                                "No oftener than", cycle.adjustEverySec,
                                { v -> vm.updateCycle { it.copy(adjustEverySec = v) } },
                                10..120, step = 5, suffix = " s",
                            )
                            NumberStepper(
                                "Never below", cycle.minWatts,
                                { v -> vm.updateCycle { it.copy(minWatts = v) } },
                                20..150, step = 5, suffix = " W",
                            )
                            NumberStepper(
                                "Never above", cycle.maxWatts,
                                { v -> vm.updateCycle { it.copy(maxWatts = v) } },
                                80..600, step = 10, suffix = " W",
                            )
                        }
                    }
                }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** One zone in the Settings list, carrying its own colour so the ring, the
 *  thermometer and this table can never disagree about what Z4 looks like. */
@Composable
private fun ZoneRow(
    band: com.telecomprofi.japanesewalk.core.ZoneBand,
    model: IntensityModel,
) {
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
                buildString {
                    val lo = (band.zone.low(model) * 100).toInt().coerceAtLeast(0)
                    val hi = (band.zone.high(model) * 100).toInt()
                    append("$lo–$hi%")
                    append(if (model == IntensityModel.HR_RESERVE) " HRR" else " HRmax")
                },
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
