package com.telecomprofi.japanesewalk.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telecomprofi.japanesewalk.ui.components.KeyValueRow
import com.telecomprofi.japanesewalk.ui.components.NumberStepper
import com.telecomprofi.japanesewalk.ui.components.ToggleRow
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.ui.vm.MusicViewModel

private enum class MusicPanel { LIBRARY, HELP, OPTIONS }

/**
 * Two panes, one above the other: the playlist that will actually play, and
 * below it the searchable library you add to it from. Each scrolls on its own,
 * so a track travels upward from the list you found it in to the list that
 * plays it.
 *
 * Help and pace-matching settings swap in for the panes rather than pushing
 * them off-screen.
 */
@Composable
fun MusicScreen(vm: MusicViewModel = viewModel()) {
    val context = LocalContext.current
    val music by vm.music.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val catalogueCount by vm.catalogueCount.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val report by vm.report.collectAsState()
    val progress by vm.progress.collectAsState()
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    var panel by remember { mutableStateOf(MusicPanel.LIBRARY) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.setFolder(uri.toString())
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }

    val analysed = tracks.count { it.analysed }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp, bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${tracks.size} in playlist · $analysed measured",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                panel = if (panel == MusicPanel.OPTIONS) MusicPanel.LIBRARY else MusicPanel.OPTIONS
            }) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = "Options",
                    tint = if (panel == MusicPanel.OPTIONS) JwColors.Slow else JwColors.Muted,
                )
            }
            IconButton(onClick = {
                panel = if (panel == MusicPanel.HELP) MusicPanel.LIBRARY else MusicPanel.HELP
            }) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "Help",
                    tint = if (panel == MusicPanel.HELP) JwColors.Accent else JwColors.Muted,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text(if (music.musicTreeUri == null) "Scan folder" else "Rescan") }
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("audio/*")) },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Add files") }
        }

        if (busy) {
            Spacer(Modifier.height(8.dp))
            val p = progress
            Text(
                status ?: "Working…",
                style = MaterialTheme.typography.labelSmall,
                color = JwColors.Accent,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            if (p != null && p.total > 0) {
                LinearProgressIndicator(
                    progress = { p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { vm.cancel() }) { Text("Cancel") }
        }

        report?.error?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = JwColors.Danger,
                maxLines = 4,
            )
        }

        when (panel) {
            MusicPanel.HELP -> HelpPanel(Modifier.weight(1f))
            MusicPanel.OPTIONS -> OptionsPanel(vm, Modifier.weight(1f))
            MusicPanel.LIBRARY -> {
                // ---- upper pane: the playlist ----
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PLAYLIST",
                        style = MaterialTheme.typography.labelSmall,
                        color = JwColors.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    if (tracks.isNotEmpty()) {
                        TextButton(onClick = { vm.reanalyseAll() }, enabled = !busy) {
                            Text("Re-measure", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { vm.clearWorkout() }, enabled = !busy) {
                            Text("Clear", style = MaterialTheme.typography.labelSmall, color = JwColors.Danger)
                        }
                    }
                }
                Pane(Modifier.weight(1f)) {
                    if (tracks.isEmpty()) {
                        item(key = "empty-playlist") {
                            Text(
                                "Nothing queued yet — add tracks from the library below.",
                                style = MaterialTheme.typography.labelSmall,
                                color = JwColors.Muted,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    items(tracks, key = { "t-${it.uri}" }) { track ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    track.artist ?: track.fileName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = JwColors.Muted,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                if (track.analysed) "${track.bpm.toInt()}" else "—",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (track.analysed) JwColors.Accent else JwColors.Muted,
                            )
                            IconButton(onClick = { vm.remove(track) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = JwColors.Muted)
                            }
                        }
                    }
                }

                // ---- lower pane: the library you pick from ----
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.setQuery(it) },
                    label = { Text("Search library ($catalogueCount tracks)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${results.size} match${if (results.size == 1) "" else "es"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JwColors.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    if (results.isNotEmpty()) {
                        TextButton(onClick = { vm.addAllResults() }, enabled = !busy) {
                            Text("Add all", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Pane(Modifier.weight(1f)) {
                    if (catalogueCount == 0) {
                        item(key = "empty-library") {
                            Text(
                                "Scan a folder to build the library, or use Add files.",
                                style = MaterialTheme.typography.labelSmall,
                                color = JwColors.Muted,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    items(results, key = { "c-${it.uri}" }) { item ->
                        val queued = tracks.any { it.uri == item.uri }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (queued) JwColors.Muted else JwColors.OnSurface,
                                    maxLines = 1,
                                )
                                Text(
                                    item.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = JwColors.Muted,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { vm.addFromCatalogue(item.uri) }, enabled = !queued) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Add",
                                    tint = if (queued) JwColors.Outline else JwColors.Slow,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A bounded, independently scrolling list pane. */
@Composable
private fun Pane(
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JwColors.Surface),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp), content = content)
    }
}

@Composable
private fun HelpPanel(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
        Text(
            "1 — Scan folder grants access to your music (on an SD card, usually the card " +
                "root or its Music folder) and catalogues everything it finds, five levels " +
                "deep, reading each file's ID3 tags. Copies of the same song in more than " +
                "one place are collapsed to one, and Android/ is skipped because that is " +
                "where per-app private duplicates live.\n\n" +
                "2 — Search the library in the lower pane by title, artist, album or file " +
                "name, then tap + to move a track up into the playlist. Add files picks " +
                "individual files straight from the system picker instead, including from " +
                "outside the scanned folder.\n\n" +
                "3 — Each track in the playlist gets its tempo measured on-device once, " +
                "then cached. During a walk the app picks the track closest to the target " +
                "cadence and time-stretches it, pitch-corrected, onto the beat.\n\n" +
                "The number beside each playlist entry is its measured tempo in BPM; a dash " +
                "means it could not be measured, and that track is then played without " +
                "pace matching.\n\n" +
                "Prefer your own player? Turn on \"Use my other music app instead\" under " +
                "the sliders icon. Voice cues still come through and duck whatever is playing.",
            style = MaterialTheme.typography.bodyMedium,
            color = JwColors.Muted,
        )
    }
}

@Composable
private fun OptionsPanel(vm: MusicViewModel, modifier: Modifier = Modifier) {
    val music by vm.music.collectAsState()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        ToggleRow(
            label = "Play music during walks",
            checked = music.enabled,
            onChange = { value -> vm.update { it.copy(enabled = value) } },
        )
        ToggleRow(
            label = "Use my other music app instead",
            checked = music.useExternalPlayer,
            onChange = { value -> vm.update { it.copy(useExternalPlayer = value) } },
        )
        ToggleRow(
            label = "Match tempo to cadence",
            checked = music.matchBpm,
            onChange = { value -> vm.update { it.copy(matchBpm = value) } },
        )
        ToggleRow(
            label = "Allow half/double time",
            checked = music.allowOctaveMatch,
            onChange = { value -> vm.update { it.copy(allowOctaveMatch = value) } },
        )
        NumberStepper(
            label = "Maximum time-stretch",
            value = music.maxStretchPercent,
            onChange = { value -> vm.update { it.copy(maxStretchPercent = value) } },
            range = 0..20,
            suffix = "%",
        )
        KeyValueRow("Volume", "${music.volumePercent}%")
        Slider(
            value = music.volumePercent.toFloat(),
            onValueChange = { value -> vm.update { it.copy(volumePercent = value.toInt()) } },
            valueRange = 0f..100f,
        )
        KeyValueRow("Duck while coaching", "${music.duckPercent}%")
        Slider(
            value = music.duckPercent.toFloat(),
            onValueChange = { value -> vm.update { it.copy(duckPercent = value.toInt()) } },
            valueRange = 0f..80f,
        )
    }
}
