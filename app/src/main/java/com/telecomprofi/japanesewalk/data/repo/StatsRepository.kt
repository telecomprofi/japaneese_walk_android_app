package com.telecomprofi.japanesewalk.data.repo

import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.SessionDots
import com.telecomprofi.japanesewalk.core.TimeMath
import com.telecomprofi.japanesewalk.data.db.AppDatabase
import com.telecomprofi.japanesewalk.data.prefs.AppPrefs
import com.telecomprofi.japanesewalk.game.Reminder
import com.telecomprofi.japanesewalk.game.ReminderCopy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Read side of the stats: this week against the 150-minute goal, today, and the
 * calendar month. Everything is derived from the local database; nothing is
 * uploaded anywhere.
 */
class StatsRepository(
    private val db: AppDatabase,
    private val prefs: AppPrefs,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = db.sessionDao()

    /** Re-emits every minute so "days left in the week" stays honest. */
    private val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(60_000)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val weekStats: Flow<WeekStats> =
        combine(minuteTicker, prefs.settings, sessions.sessionCount()) { now, settings, _ ->
            now to settings.reminders.weeklyTargetMinutes
        }.mapLatest { (now, target) -> buildWeekStats(now, target) }

    /** Today + this week + this month, for the Summary screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val summary: Flow<SummaryStats> =
        combine(minuteTicker, prefs.settings, sessions.sessionCount()) { now, settings, _ ->
            now to settings.reminders.weeklyTargetMinutes
        }.mapLatest { (now, target) -> buildSummary(now, target) }

    suspend fun buildWeekStats(now: Long = clock(), targetMinutes: Int? = null): WeekStats {
        val target = targetMinutes ?: prefs.settings.first().reminders.weeklyTargetMinutes
        val weekStart = TimeMath.startOfWeekMillis(now)
        val stats = sessions.statsBetweenOnce(weekStart, weekStart + WEEK_MS)
        val dayStart = TimeMath.startOfDayMillis(now)
        val todaySec = sessions.statsBetweenOnce(dayStart, dayStart + DAY_MS).totalSec
        return WeekStats(
            weekStartMillis = weekStart,
            minutes = stats.totalSec / 60,
            targetMinutes = target,
            kcal = stats.kcal,
            sessions = stats.sessions,
            daysLeft = TimeMath.daysLeftInWeek(now),
            walkedToday = todaySec > 0,
        )
    }

    suspend fun buildSummary(now: Long = clock(), targetMinutes: Int? = null): SummaryStats {
        val target = targetMinutes ?: prefs.settings.first().reminders.weeklyTargetMinutes
        val week = buildWeekStats(now, target)

        val dayStart = TimeMath.startOfDayMillis(now)
        val today = sessions.statsBetweenOnce(dayStart, dayStart + DAY_MS)

        val weekStart = TimeMath.startOfWeekMillis(now)
        val monthStart = TimeMath.startOfMonthMillis(now)
        val month = sessions.statsBetweenOnce(monthStart, TimeMath.endOfMonthMillis(now))

        // Only weeks that actually start inside the month count, otherwise the
        // week straddling the 1st gets credited here with last month's minutes.
        var weeksElapsed = 0
        var weeksOnTarget = 0
        var cursor = weekStart
        while (cursor >= monthStart && weeksElapsed < 6) {
            weeksElapsed++
            if (sessions.statsBetweenOnce(cursor, cursor + WEEK_MS).totalSec / 60 >= target) weeksOnTarget++
            cursor -= WEEK_MS
        }
        // On any month whose 1st is not a Monday, the current week began last
        // month and the loop above counts nothing. Count it as this month's.
        if (weeksElapsed == 0) {
            weeksElapsed = 1
            if (week.achieved) weeksOnTarget = 1
        }

        val weekCompleted = sessions.completedBetween(weekStart, weekStart + WEEK_MS)
        val sessionMinutes = IntervalPlan(prefs.settings.first().protocol).totalSec / 60

        return SummaryStats(
            dots = SessionDots.of(
                completed = weekCompleted,
                partial = (week.sessions - weekCompleted).coerceAtLeast(0),
                targetMinutes = target,
                sessionMinutes = sessionMinutes,
            ),
            todayMinutes = today.totalSec / 60,
            todayKcal = today.kcal,
            todaySessions = today.sessions,
            week = week,
            monthLabel = TimeMath.monthName(now),
            monthMinutes = month.totalSec / 60,
            monthKcal = month.kcal,
            monthSessions = month.sessions,
            monthAvgHr = month.avgHr.toInt(),
            monthWeeksOnTarget = weeksOnTarget,
            monthWeeksElapsed = weeksElapsed,
        )
    }

    suspend fun reminder(now: Long = clock()): Reminder {
        val settings = prefs.settings.first()
        val week = buildWeekStats(now, settings.reminders.weeklyTargetMinutes)
        return ReminderCopy.forWeek(
            weekMinutes = week.minutes,
            targetMinutes = week.targetMinutes,
            daysLeftInWeek = week.daysLeft,
            sessionMinutes = IntervalPlan(settings.protocol).totalSec / 60,
            walkedToday = week.walkedToday,
        )
    }
}
