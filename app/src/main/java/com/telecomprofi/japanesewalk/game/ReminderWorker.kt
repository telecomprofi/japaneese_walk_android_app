package com.telecomprofi.japanesewalk.game

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.telecomprofi.japanesewalk.Graph
import com.telecomprofi.japanesewalk.core.ReminderTime
import com.telecomprofi.japanesewalk.data.prefs.ReminderSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Daily check-in that turns the weekly 150-minute target into a nudge. Runs
 * entirely on-device via WorkManager, so it survives reboots.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val container = Graph.container
        val settings = container.prefs.settings.first()
        if (!settings.reminders.enabled) return Result.success()

        val week = container.statsRepository.buildWeekStats()
        val reminder = container.statsRepository.reminder()
        Notifications.postReminder(applicationContext, reminder, week)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "daily_iwt_reminder"
    }
}

object ReminderScheduler {

    fun apply(context: Context, settings: ReminderSettings) {
        val manager = WorkManager.getInstance(context)
        if (!settings.enabled) {
            manager.cancelUniqueWork(ReminderWorker.WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(
                ReminderTime.initialDelayMinutes(settings.hourOfDay, settings.minuteOfHour),
                TimeUnit.MINUTES,
            )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()
        manager.enqueueUniquePeriodicWork(
            ReminderWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
