package com.telecomprofi.japanesewalk.game

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.telecomprofi.japanesewalk.MainActivity
import com.telecomprofi.japanesewalk.R
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.TimeMath
import com.telecomprofi.japanesewalk.data.repo.WeekStats
import com.telecomprofi.japanesewalk.workout.EngineStatus
import com.telecomprofi.japanesewalk.workout.WorkoutService
import com.telecomprofi.japanesewalk.workout.WorkoutUiState

object Notifications {

    const val CHANNEL_WORKOUT = "workout_live"
    const val CHANNEL_REMINDERS = "weekly_reminders"
    const val ID_WORKOUT = 1001
    const val ID_REMINDER = 1002
    const val ID_WEEKLY_SUMMARY = 1003

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WORKOUT,
                "Live workout",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Heart rate and interval progress while you walk"
                setShowBadge(false)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Weekly goal reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Daily note on progress towards your weekly minutes" }
        )
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, WorkoutService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun workout(
        context: Context,
        state: WorkoutUiState,
        musicMuted: Boolean = false,
    ): android.app.Notification {
        val phaseText = when (state.phase) {
            Phase.FAST -> "Fast"
            Phase.SLOW -> "Slow"
            Phase.WARMUP -> "Warm-up"
            Phase.COOLDOWN -> "Cool-down"
            null -> "Ready"
        }
        val title = buildString {
            if (state.isCooling) {
                append("Cool-down – still counting")
            } else {
                append(phaseText)
                if (state.setNumber > 0) append(" – set ${state.setNumber}/${state.totalSets}")
            }
            if (state.status == EngineStatus.PAUSED) {
                append(if (state.interrupted) " (call)" else " (paused)")
            }
        }
        val hrText = state.hr?.let { "$it bpm" } ?: "-- bpm"
        val zoneText = state.zone?.shortLabel ?: "--"
        val body = if (state.isCooling) {
            "$hrText · $zoneText · ${TimeMath.mmss(state.coolDownSec)} extra · ${state.kcal} kcal"
        } else {
            "$hrText · $zoneText · ${TimeMath.mmss(state.segmentRemainingSec)} left · " +
                "${TimeMath.mmss(state.remainingSec)} to go · ${state.kcal} kcal"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_WORKOUT)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(title)
            .setContentText(body)
            .setOnlyAlertOnce(true)
            .setOngoing(state.status == EngineStatus.RUNNING || state.isCooling)
            .setSilent(true)
            .setContentIntent(contentIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(state.totalSec, state.planElapsedSec, false)

        if (state.status == EngineStatus.RUNNING) {
            builder.addAction(
                R.drawable.ic_stat_walk, "Pause", serviceAction(context, WorkoutService.ACTION_PAUSE, 11)
            )
        } else if (state.status == EngineStatus.PAUSED) {
            builder.addAction(
                R.drawable.ic_stat_walk, "Resume", serviceAction(context, WorkoutService.ACTION_RESUME, 12)
            )
        }
        // Music controls in the shade, so the playlist can be silenced or
        // skipped without unlocking and finding the app.
        if (state.nowPlaying != null) {
            builder.addAction(
                R.drawable.ic_stat_walk,
                if (musicMuted) "Play music" else "Mute music",
                serviceAction(context, WorkoutService.ACTION_TOGGLE_MUSIC, 15),
            )
            builder.addAction(
                R.drawable.ic_stat_walk, "Next track", serviceAction(context, WorkoutService.ACTION_NEXT_TRACK, 14)
            )
        }
        builder.addAction(
            R.drawable.ic_stat_walk, "Finish", serviceAction(context, WorkoutService.ACTION_STOP, 13)
        )
        return builder.build()
    }

    fun postReminder(context: Context, reminder: Reminder, week: WeekStats) {
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(reminder.title)
            .setContentText(reminder.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.body))
            .setProgress(week.targetMinutes, week.minutes.coerceAtMost(week.targetMinutes), false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        safeNotify(context, ID_REMINDER, notification)
    }

    /** The Sunday-morning look back. Same channel as the daily nudge. */
    fun postWeeklySummary(context: Context, table: com.telecomprofi.japanesewalk.data.repo.WeekTable) {
        val body = WeeklySummaryCopy.body(table)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(WeeklySummaryCopy.title(table))
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body + "\n\n" + WeeklySummaryCopy.line(table))
            )
            .setProgress(
                table.targetMinutes,
                table.minutes.coerceAtMost(table.targetMinutes),
                false,
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        safeNotify(context, ID_WEEKLY_SUMMARY, notification)
    }

    private fun safeNotify(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; nothing to do.
        }
    }
}
