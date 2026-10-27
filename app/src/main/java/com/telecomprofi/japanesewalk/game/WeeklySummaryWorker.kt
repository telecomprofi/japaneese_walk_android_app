package com.telecomprofi.japanesewalk.game

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.core.ReminderTime
import com.telecomprofi.japanesewalk.data.prefs.ReminderSettings
import com.telecomprofi.japanesewalk.data.repo.WeekTable
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.util.concurrent.TimeUnit

/**
 * Sunday morning: what the week actually came to.
 *
 * Deliberately at the end of the week rather than the start of the next one —
 * on Sunday at eleven there is still a day left to fix a week that came up
 * short, which is the only reason to be told about it.
 */
class WeeklySummaryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val container = Graph.container
        val settings = container.prefs.settings.first()
        if (!settings.reminders.weeklySummary) return Result.success()

        val table = container.statsRepository.buildWeekTable()
        Notifications.postWeeklySummary(applicationContext, table)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "weekly_summary"
    }
}

object WeeklySummaryScheduler {

    fun apply(context: Context, settings: ReminderSettings) {
        val manager = WorkManager.getInstance(context)
        if (!settings.weeklySummary) {
            manager.cancelUniqueWork(WeeklySummaryWorker.WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<WeeklySummaryWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(
                ReminderTime.weeklyDelayMinutes(DayOfWeek.SUNDAY, settings.summaryHourOfDay),
                TimeUnit.MINUTES,
            )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()
        manager.enqueueUniquePeriodicWork(
            WeeklySummaryWorker.WORK_NAME,
            // REPLACE, not UPDATE: changing the hour has to move the next run,
            // and UPDATE keeps the schedule the work was first enqueued with.
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }
}

/** The wording of the Sunday note, kept out of the notification plumbing. */
object WeeklySummaryCopy {

    fun title(table: WeekTable): String = when {
        table.sessions == 0 -> "A quiet week"
        table.achieved -> "${table.minutes} minutes — target cleared"
        else -> "${table.minutes} of ${table.targetMinutes} minutes"
    }

    fun body(table: WeekTable): String {
        if (table.sessions == 0) {
            return "Nothing recorded this week. One session today still counts — " +
                "${table.targetMinutes} minutes a week is the goal, and any of it beats none."
        }
        val days = "${table.activeDays} ${if (table.activeDays == 1) "day" else "days"}"
        val hr = if (table.avgHr > 0) ", average ${table.avgHr} bpm" else ""
        val tail = if (table.achieved) {
            "That is the week done."
        } else {
            "${(table.targetMinutes - table.minutes).coerceAtLeast(0)} minutes short — " +
                "today is the last day of the week."
        }
        return "${table.sessions} sessions across $days, ${table.kcal} calories$hr. $tail"
    }

    /** Mon–Sun, one line, for the expanded notification: "Mon 30 · Tue — · …". */
    fun line(table: WeekTable): String = table.days.joinToString("  ·  ") { day ->
        "${day.label} ${if (day.minutes > 0) "${day.minutes}m" else "—"}"
    }
}
