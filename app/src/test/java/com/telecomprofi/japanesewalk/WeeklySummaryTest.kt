package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.ReminderTime
import com.telecomprofi.japanesewalk.data.db.SessionEntity
import com.telecomprofi.japanesewalk.data.repo.DayStat
import com.telecomprofi.japanesewalk.data.repo.WeekTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

private fun session(
    elapsedSec: Int = 1800,
    avgHr: Int = 120,
    z1: Int = 0,
    z2: Int = 0,
    z3: Int = 0,
    z4: Int = 0,
    z5: Int = 0,
    onTarget: Int = 0,
    offTarget: Int = 0,
    mode: Int = 0,
) = SessionEntity(
    startedAt = 0L,
    endedAt = elapsedSec * 1000L,
    plannedSec = 1800,
    elapsedSec = elapsedSec,
    avgHr = avgHr,
    maxHr = avgHr + 20,
    kcal = 200,
    z1Sec = z1, z2Sec = z2, z3Sec = z3, z4Sec = z4, z5Sec = z5,
    onTargetSec = onTarget, offTargetSec = offTarget,
    completedSets = 5, plannedSets = 5, completed = true,
    protocolLabel = "5 x 3/3",
    mode = mode,
)

private fun day(label: String, minutes: Int, kcal: Int = 0, avgHr: Int = 0, sessions: Int = if (minutes > 0) 1 else 0) =
    DayStat(0L, label, minutes, kcal, sessions, avgHr)

class WeeklySummaryTest {

    @Test
    fun `the dominant zone is where the time went, not the average of zone numbers`() {
        // Twelve minutes easy, three brisk, fifteen hard: Z1 and Z3 either side
        // of a thin Z2. An average would say Z2, which is where it never was.
        val s = session(z1 = 720, z2 = 180, z3 = 900)
        assertEquals(HrZone.Z3, s.dominantZone)
        assertEquals(HrZone.Z1, session(z1 = 900, z2 = 180, z3 = 300).dominantZone)
    }

    @Test
    fun `the legacy fourth and fifth zones fold into the top one`() {
        val s = session(z1 = 600, z3 = 200, z4 = 300, z5 = 200)
        assertEquals(HrZone.Z3, s.dominantZone)
    }

    @Test
    fun `a session with no zone data says so rather than claiming zone one`() {
        assertFalse(session().hasZoneData)
        assertTrue(session(z1 = 60).hasZoneData)
    }

    @Test
    fun `adherence is the share of time inside the band`() {
        assertEquals(75, session(onTarget = 900, offTarget = 300).adherencePercent)
        assertEquals(0, session().adherencePercent)
    }

    @Test
    fun `the week totals what the days hold`() {
        val table = WeekTable(
            weekStartMillis = 0L,
            days = listOf(
                day("Mon", 30, 210, 118), day("Tue", 0), day("Wed", 45, 320, 126),
                day("Thu", 0), day("Fri", 30, 205, 121), day("Sat", 0), day("Sun", 0),
            ),
            targetMinutes = 150,
        )
        assertEquals(105, table.minutes)
        assertEquals(735, table.kcal)
        assertEquals(3, table.sessions)
        assertEquals(3, table.activeDays)
        assertEquals(45, table.busiestMinutes)
        assertFalse(table.achieved)
    }

    @Test
    fun `the week's average heart rate is weighted by minutes, not by day`() {
        val table = WeekTable(
            weekStartMillis = 0L,
            days = listOf(day("Mon", 90, avgHr = 130), day("Tue", 10, avgHr = 100)),
        )
        // A plain mean of the two days would be 115; the long day dominates.
        assertEquals(127, table.avgHr)
    }

    @Test
    fun `an empty week reports zero rather than dividing by it`() {
        val table = WeekTable(0L, listOf(day("Mon", 0), day("Tue", 0)))
        assertEquals(0, table.avgHr)
        assertEquals(0, table.minutes)
        assertEquals(1, table.busiestMinutes.coerceAtLeast(1))
        assertFalse(table.achieved)
    }

    @Test
    fun `hitting the target is measured against the target`() {
        val days = listOf(day("Mon", 150, 900, 120))
        assertTrue(WeekTable(0L, days, targetMinutes = 150).achieved)
        assertFalse(WeekTable(0L, days, targetMinutes = 180).achieved)
    }

    @Test
    fun `the Sunday summary waits for the next Sunday`() {
        // Wednesday 2026-08-26 at 09:00 -> Sunday the 30th at 11:00.
        val wed = LocalDateTime.of(2026, 8, 26, 9, 0)
        val minutes = ReminderTime.weeklyDelayMinutes(DayOfWeek.SUNDAY, 11, 0, wed)
        assertEquals((4L * 24 + 2) * 60, minutes)
    }

    @Test
    fun `on Sunday morning it fires the same day`() {
        val sunEarly = LocalDateTime.of(2026, 8, 30, 8, 30)
        assertEquals(150L, ReminderTime.weeklyDelayMinutes(DayOfWeek.SUNDAY, 11, 0, sunEarly))
    }

    @Test
    fun `after the hour on Sunday it waits a whole week`() {
        val sunLate = LocalDateTime.of(2026, 8, 30, 11, 30)
        assertEquals((7L * 24 * 60) - 30, ReminderTime.weeklyDelayMinutes(DayOfWeek.SUNDAY, 11, 0, sunLate))
    }

    @Test
    fun `the delay is never zero, which WorkManager reads as now`() {
        val exactly = LocalDateTime.of(2026, 8, 30, 11, 0)
        assertTrue(ReminderTime.weeklyDelayMinutes(DayOfWeek.SUNDAY, 11, 0, exactly) >= 1)
    }
}
