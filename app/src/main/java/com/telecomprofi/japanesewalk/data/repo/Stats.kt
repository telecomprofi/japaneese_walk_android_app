package com.telecomprofi.japanesewalk.data.repo

import com.telecomprofi.japanesewalk.core.SessionDot
import kotlin.math.roundToInt

data class WeekStats(
    val weekStartMillis: Long,
    val minutes: Int,
    val targetMinutes: Int,
    val kcal: Int,
    val sessions: Int,
    val daysLeft: Int,
    val walkedToday: Boolean,
) {
    val percentOfTarget: Int
        get() = if (targetMinutes == 0) 0 else ((minutes * 100.0) / targetMinutes).roundToInt()

    val minutesRemaining: Int get() = (targetMinutes - minutes).coerceAtLeast(0)
    val achieved: Boolean get() = minutes >= targetMinutes

    companion object {
        val EMPTY = WeekStats(0L, 0, 150, 0, 0, 7, false)
    }
}

/**
 * Everything the Summary screen shows: today, this week against the 150-minute
 * goal, and the calendar month to date.
 */
data class SummaryStats(
    val todayMinutes: Int = 0,
    val todayKcal: Int = 0,
    val todaySessions: Int = 0,
    val week: WeekStats = WeekStats.EMPTY,
    val monthLabel: String = "",
    val monthMinutes: Int = 0,
    val monthKcal: Int = 0,
    val monthSessions: Int = 0,
    val monthAvgHr: Int = 0,
    /** How many of the month's weeks so far hit the target. */
    val monthWeeksOnTarget: Int = 0,
    val monthWeeksElapsed: Int = 0,
    /** One circle per session the weekly target implies. */
    val dots: List<SessionDot> = emptyList(),
) {
    val monthMinutesPerWeek: Int
        get() = if (monthWeeksElapsed == 0) 0 else monthMinutes / monthWeeksElapsed

    companion object { val EMPTY = SummaryStats() }
}
