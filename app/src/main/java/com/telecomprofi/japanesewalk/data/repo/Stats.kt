package com.telecomprofi.japanesewalk.data.repo

import com.telecomprofi.japanesewalk.core.SessionCircle
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
 * One day of the week table: what was done, and in which mode. Days with
 * nothing in them are kept — a blank Wednesday is information.
 */
data class DayStat(
    val dayStartMillis: Long,
    /** "Mon", "Tue"… in the phone's locale. */
    val label: String,
    val minutes: Int,
    val kcal: Int,
    val sessions: Int,
    val avgHr: Int,
    val modes: List<com.telecomprofi.japanesewalk.core.WorkoutMode> = emptyList(),
    val isToday: Boolean = false,
) {
    val didSomething: Boolean get() = sessions > 0
}

/** Seven days, Monday first, with the totals that go under the rule. */
data class WeekTable(
    val weekStartMillis: Long,
    val days: List<DayStat> = emptyList(),
    val targetMinutes: Int = 150,
) {
    val minutes: Int get() = days.sumOf { it.minutes }
    val kcal: Int get() = days.sumOf { it.kcal }
    val sessions: Int get() = days.sumOf { it.sessions }
    val activeDays: Int get() = days.count { it.didSomething }
    val busiestMinutes: Int get() = days.maxOfOrNull { it.minutes } ?: 0
    val achieved: Boolean get() = minutes >= targetMinutes
    val avgHr: Int
        get() {
            val weighted = days.filter { it.avgHr > 0 && it.minutes > 0 }
            if (weighted.isEmpty()) return 0
            return weighted.sumOf { it.avgHr * it.minutes } / weighted.sumOf { it.minutes }
        }

    companion object { val EMPTY = WeekTable(0L) }
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
    val dots: List<SessionCircle> = emptyList(),
) {
    val monthMinutesPerWeek: Int
        get() = if (monthWeeksElapsed == 0) 0 else monthMinutes / monthWeeksElapsed

    companion object { val EMPTY = SummaryStats() }
}
