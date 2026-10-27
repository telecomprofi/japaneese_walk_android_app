package com.telecomprofi.japanesewalk.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Week handling for the weekly 150-minute goal. Weeks start on Monday. */
object TimeMath {

    const val WEEKLY_TARGET_MINUTES = 150

    fun startOfWeekMillis(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun endOfWeekMillis(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        startOfWeekMillis(nowMillis, zone) + 7L * 24 * 60 * 60 * 1000

    fun startOfMonthMillis(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfMonthMillis(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun monthName(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        localDate(nowMillis, zone).month
            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())

    fun startOfDayMillis(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun localDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Days remaining in the current week, counting today. 1 on Sunday. */
    fun daysLeftInWeek(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val dow = Instant.ofEpochMilli(nowMillis).atZone(zone).dayOfWeek.value // Mon=1
        return 8 - dow
    }

    fun startOfWeekMillisOffset(nowMillis: Long, weeksAgo: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        startOfWeekMillis(nowMillis, zone) - weeksAgo * 7L * 24 * 60 * 60 * 1000

    /** "Mon", "Tue"… in the phone's own language. */
    fun dayName(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        localDate(millis, zone).dayOfWeek.getDisplayName(
            java.time.format.TextStyle.SHORT,
            java.util.Locale.getDefault(),
        )

    /** "14:05" in the phone's own clock format-agnostic 24h form. */
    fun timeOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    fun mmss(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun hhmmss(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    fun humanMinutes(totalSec: Int): String {
        val m = totalSec / 60
        return if (m < 60) "$m min" else "%dh %02dm".format(m / 60, m % 60)
    }
}
