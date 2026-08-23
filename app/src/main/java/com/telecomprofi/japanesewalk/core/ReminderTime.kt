package com.telecomprofi.japanesewalk.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When the next daily reminder should fire. Pure so the arithmetic can be
 * tested: "the time has already passed today, so tomorrow" is exactly the kind
 * of thing that silently misfires for a day at a time.
 */
object ReminderTime {

    /** Minutes from [now] until the next occurrence of [hourOfDay]:[minuteOfHour]. */
    fun initialDelayMinutes(
        hourOfDay: Int,
        minuteOfHour: Int = 0,
        now: LocalDateTime = LocalDateTime.now(),
    ): Long {
        val time = LocalTime.of(hourOfDay.coerceIn(0, 23), minuteOfHour.coerceIn(0, 59))
        val today = LocalDateTime.of(now.toLocalDate(), time)
        val target = if (today.isAfter(now)) today else today.plusDays(1)
        // WorkManager treats a zero delay as "run now", which would fire the
        // reminder at an arbitrary moment rather than the requested one.
        return Duration.between(now, target).toMinutes().coerceAtLeast(1)
    }

    fun format(hourOfDay: Int, minuteOfHour: Int): String =
        "%02d:%02d".format(hourOfDay.coerceIn(0, 23), minuteOfHour.coerceIn(0, 59))
}
