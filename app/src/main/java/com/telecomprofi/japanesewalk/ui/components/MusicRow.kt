package com.telecomprofi.japanesewalk.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.telecomprofi.japanesewalk.core.TimeMath
import com.telecomprofi.japanesewalk.music.MusicStatus
import com.telecomprofi.japanesewalk.music.NowPlaying
import com.telecomprofi.japanesewalk.ui.theme.JwColors
import com.telecomprofi.japanesewalk.workout.WorkoutService

/**
 * The now-playing block: one scrolling line of track detail plus Mute and Skip.
 *
 * Lives here rather than inside the Walk screen because the Data screen shows
 * the identical thing — mid-walk you should not have to swipe back to change
 * the track.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicRow(
    nowPlaying: NowPlaying?,
    muted: Boolean,
    status: MusicStatus = MusicStatus(),
    controlsEnabled: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = JwColors.Accent,
    showControls: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        if (showControls || leading != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    Row(Modifier.weight(1f)) { leading() }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (showControls && controlsEnabled && nowPlaying != null) {
                    OutlinedButton(
                        onClick = { WorkoutService.send(context, WorkoutService.ACTION_TOGGLE_MUSIC) },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) { Text(if (muted) "Unmute" else "Mute") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { WorkoutService.send(context, WorkoutService.ACTION_NEXT_TRACK) },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) { Text("Skip track") }
                }
            }
        }

        if (nowPlaying != null) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // The one line that makes silence readable: where the track
                    // is, and how loud it actually is after levelling and any
                    // duck. "0:41 / 3:58 · 4%" is a stuck duck; "paused" is not.
                    buildString {
                        if (status.hasDuration) {
                            append(TimeMath.mmss((status.positionMs / 1000).toInt()))
                            append(" / ")
                            append(TimeMath.mmss((status.durationMs / 1000).toInt()))
                        } else {
                            append("--:--")
                        }
                        append("  ·  ")
                        append(
                            when {
                                status.muted -> "muted"
                                !status.playing -> "paused"
                                status.ducked -> "ducked ${status.volumePercent}%"
                                else -> "${status.volumePercent}%"
                            }
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        status.muted || !status.playing -> JwColors.Muted
                        status.ducked -> JwColors.Gold
                        else -> JwColors.Muted
                    },
                )
            }
            Text(
                nowPlaying.line(),
                style = MaterialTheme.typography.titleMedium,
                color = if (muted) JwColors.Muted else accent,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .basicMarquee(),
            )
        }
    }
}

/** One line of everything worth knowing about the current track. */
fun NowPlaying.line(): String = buildString {
    append(title)
    artist?.let { append(" — $it") }
    append("   ·   ${trackBpm.toInt()} bpm")
    if (multiplier != 1.0) append(" x$multiplier")
    append(" → ${effectiveBpm.toInt()} bpm")
    if (stretchPercent != 0) {
        append(" (${if (stretchPercent > 0) "+" else ""}$stretchPercent%)")
    }
    append("   ·   target $targetBpm spm")
    if (levelled) append("   ·   ${"%.1f".format(gainDb)} dB")
}
