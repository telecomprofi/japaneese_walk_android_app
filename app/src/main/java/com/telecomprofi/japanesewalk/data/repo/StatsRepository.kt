package com.telecomprofi.japanesewalk.data.repo

import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.SessionDots
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.core.DayLoad
import com.telecomprofi.japanesewalk.core.TimeMath
import com.telecomprofi.japanesewalk.core.TrainingLoad
import com.telecomprofi.japanesewalk.core.TrainingStatus
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

    /** Fitness, fatigue and what to do today, from the last six weeks of walks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val trainingStatus: Flow<TrainingStatus> =
        combine(minuteTicker, prefs.settings, sessions.sessionCount()) { now, _, _ -> now }
            .mapLatest { now -> buildTrainingStatus(now) }

    /**
     * Six weeks of daily load. The window is the fitness time constant: shorter
     * and the slow average never settles, longer and it is remembering walks
     * that stopped mattering.
     */
    suspend fun buildTrainingStatus(now: Long = clock()): TrainingStatus {
        val profile = prefs.settings.first().profile
        val from = TimeMath.startOfDayMillis(now) - (TrainingLoad.FITNESS_DAYS.toLong() - 1) * DAY_MS
        val rows = sessions.betweenOnce(from, now + DAY_MS)
        val perSession = rows.map { session ->
            DayLoad(
                date = TimeMath.localDate(session.startedAt),
                trimp = TrainingLoad.trimp(
                    minutes = session.elapsedSec / 60.0,
                    avgHr = session.avgHr,
                    restingHr = profile.restingHr,
                    maxHr = profile.maxHr,
                    isMale = profile.isMale,
                ),
                minutes = session.elapsedSec / 60,
                sessions = 1,
            )
        }
        val days = TrainingLoad.fill(
            days = perSession,
            from = TimeMath.localDate(from),
            to = TimeMath.localDate(now),
        )
        return TrainingLoad.status(days)
    }

    /** The seven-day table, rebuilt whenever a session lands or the minute turns. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val weekTable: Flow<WeekTable> =
        combine(minuteTicker, prefs.settings, sessions.sessionCount()) { now, settings, _ ->
            now to settings.reminders.weeklyTargetMinutes
        }.mapLatest { (now, target) -> buildWeekTable(now, target) }

    /**
     * Monday to Sunday of the week containing [now]. Days are built from the
     * sessions themselves rather than seven aggregate queries: a week is a
     * handful of rows, and this way each day can also say what it was spent on.
     */
    suspend fun buildWeekTable(now: Long = clock(), targetMinutes: Int? = null): WeekTable {
        val target = targetMinutes ?: prefs.settings.first().reminders.weeklyTargetMinutes
        val weekStart = TimeMath.startOfWeekMillis(now)
        val rows = sessions.betweenOnce(weekStart, weekStart + WEEK_MS)
        val today = TimeMath.startOfDayMillis(now)

        val days = (0 until 7).map { index ->
            // Built by date, not by adding 24 hours seven times: the week a
            // clock change falls in has a 23- and a 25-hour day in it.
            val dayStart = TimeMath.startOfDayMillis(weekStart + index * DAY_MS + DAY_MS / 2)
            val dayEnd = TimeMath.startOfDayMillis(weekStart + (index + 1) * DAY_MS + DAY_MS / 2)
            val ofDay = rows.filter { it.startedAt in dayStart until dayEnd }
            val seconds = ofDay.sumOf { it.elapsedSec }
            DayStat(
                dayStartMillis = dayStart,
                label = TimeMath.dayName(dayStart),
                minutes = seconds / 60,
                kcal = ofDay.sumOf { it.kcal },
                sessions = ofDay.size,
                avgHr = if (seconds == 0) 0 else
                    ofDay.sumOf { it.avgHr * it.elapsedSec } / seconds,
                modes = ofDay.map { it.workoutMode },
                isToday = dayStart == today,
            )
        }
        return WeekTable(weekStartMillis = weekStart, days = days, targetMinutes = target)
    }

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
        val weekModes = sessions.completedModesBetween(weekStart, weekStart + WEEK_MS)
            .map { WorkoutMode.entries.getOrElse(it) { WorkoutMode.WALK } }
        val sessionMinutes = prefs.settings.first().let { IntervalPlan(it.protocol, it.profile.intensityModel) }.totalSec / 60

        return SummaryStats(
            dots = SessionDots.withModes(
                completedModes = weekModes,
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
            sessionMinutes = IntervalPlan(settings.protocol, settings.profile.intensityModel).totalSec / 60,
            walkedToday = week.walkedToday,
        )
    }
}
