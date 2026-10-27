package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.DayLoad
import com.telecomprofi.japanesewalk.core.TrainingLoad
import com.telecomprofi.japanesewalk.core.TrainingVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The load model, checked against the patterns it exists to tell apart.
 *
 * The numbers here were worked out before the code was written: a 30-minute
 * walk at 118 bpm for a 52-year-old with a resting rate of 60 is about 27
 * TRIMP, and the thresholds were then chosen so that every *steady* pattern —
 * however hard — reads 1.00, and only a genuine jump in load moves the ratio.
 */
class TrainingLoadTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 3)
    private val session = TrainingLoad.trimp(30.0, 118, 60, 172, isMale = true)

    /** @param pattern one entry per day, oldest first; true means a walk. */
    private fun days(pattern: List<Boolean>, minutes: Int = 30): List<DayLoad> =
        pattern.mapIndexed { i, trained ->
            DayLoad(
                date = today.minusDays((pattern.size - 1 - i).toLong()),
                trimp = if (trained) session else 0.0,
                minutes = if (trained) minutes else 0,
                sessions = if (trained) 1 else 0,
            )
        }

    private fun weeks(week: List<Boolean>, count: Int) = days(List(count) { week }.flatten())

    @Test
    fun `a half-hour brisk walk is worth about twenty-seven points`() {
        assertEquals(27.0, session, 1.5)
        // Twice as long is twice the load; harder is disproportionately more.
        assertEquals(2 * session, TrainingLoad.trimp(60.0, 118, 60, 172, true), 0.1)
        assertTrue(TrainingLoad.trimp(30.0, 140, 60, 172, true) > 1.9 * session)
    }

    @Test
    fun `a walk with no heart rate still counts for something`() {
        val noStrap = TrainingLoad.trimp(30.0, 0, 60, 172, true)
        assertTrue("minutes happened even if the strap did not", noStrap > 0)
        assertTrue("but they are not credited as brisk", noStrap < session)
    }

    @Test
    fun `steady training of any size reads one`() {
        // The point of matched averages: the ratio flags change, not volume.
        for (week in listOf(
            listOf(true, false, true, false, true, false, true),   // 4 a week
            listOf(true, false, true, false, true, false, false),  // 3 a week
            listOf(true, true, true, true, true, true, true),      // every day
        )) {
            val ratio = TrainingLoad.loadRatio(weeks(week, 12))
            assertEquals("steady pattern should read 1.00", 1.0, ratio, 0.02)
        }
    }

    @Test
    fun `a sudden week of daily walking is flagged`() {
        val steady = weeks(listOf(true, false, true, false, true, false, true), 8)
        val spike = steady + days(List(7) { true }).map { it.copy(date = it.date.plusDays(56)) }
        val ratio = TrainingLoad.loadRatio(spike)
        assertTrue("a doubling should show up, got $ratio", ratio > 1.4)
    }

    @Test
    fun `a beginner's first good week is not called overreaching`() {
        // Four walks against three weeks of nothing is arithmetically a 4.0
        // ratio, and telling a beginner doing everything right that they are
        // overreaching would be both wrong and discouraging.
        val first = days(List(21) { false } + listOf(true, false, true, false, true, false, true))
        assertFalse(TrainingLoad.hasHistory(first))
        val status = TrainingLoad.status(first)
        assertTrue(
            "got ${status.verdict}",
            status.verdict == TrainingVerdict.ON_TRACK || status.verdict == TrainingVerdict.UNDER,
        )
    }

    @Test
    fun `four walks and three rest days is on track`() {
        val status = TrainingLoad.status(weeks(listOf(true, false, true, false, true, false, true), 8))
        assertEquals(TrainingVerdict.ON_TRACK, status.verdict)
        assertEquals(4, status.weeklySessions)
        assertEquals(3, status.restDays)
        assertTrue(status.onTarget)
        assertFalse(status.restToday)
    }

    @Test
    fun `training every day asks for a rest day`() {
        val status = TrainingLoad.status(weeks(List(7) { true }, 8))
        assertEquals(TrainingVerdict.NEEDS_REST, status.verdict)
        assertEquals(0, status.restDays)
        assertTrue(status.restToday)
    }

    @Test
    fun `three days back to back is the limit`() {
        val run = days(List(21) { it % 2 == 1 } + listOf(true, true, true))
        assertEquals(3, TrainingLoad.consecutiveTrainingDays(run))
        assertEquals(TrainingVerdict.NEEDS_REST, TrainingLoad.status(run).verdict)
    }

    @Test
    fun `two walks a week is under the target`() {
        val status = TrainingLoad.status(weeks(listOf(true, false, false, true, false, false, false), 8))
        assertEquals(TrainingVerdict.UNDER, status.verdict)
        assertEquals(2, status.weeklySessions)
        assertEquals(2, status.sessionsShort)
    }

    @Test
    fun `short walks do not count towards the weekly four`() {
        val status = TrainingLoad.status(
            days(List(21) { false } + List(7) { true }, minutes = 12)
        )
        assertEquals(0, status.weeklySessions)
    }

    @Test
    fun `nothing at all reads as resting, not as a problem`() {
        val status = TrainingLoad.status(days(List(42) { false }))
        assertEquals(TrainingVerdict.RESTING, status.verdict)
        assertEquals(0.0, status.fitness, 1e-9)
        assertEquals(7, status.restDays)
    }

    @Test
    fun `an empty history does not throw`() {
        val status = TrainingLoad.status(emptyList())
        assertEquals(TrainingVerdict.RESTING, status.verdict)
        assertEquals(0.0, status.loadRatio, 1e-9)
    }

    @Test
    fun `a streak is not broken by today being still empty`() {
        val yesterdayWasFourth = days(List(20) { false } + listOf(true, true, true, true, false))
        assertEquals(4, TrainingLoad.consecutiveTrainingDays(yesterdayWasFourth))
    }

    @Test
    fun `rest decays fatigue faster than fitness`() {
        val trained = weeks(listOf(true, false, true, false, true, false, true), 8)
        val afterRest = trained + days(List(10) { false }).map { it.copy(date = it.date.plusDays(56)) }
        val (fitBefore, tiredBefore) = TrainingLoad.averages(trained)
        val (fitAfter, tiredAfter) = TrainingLoad.averages(afterRest)
        assertTrue("fatigue should fall fast", tiredAfter < tiredBefore * 0.5)
        assertTrue("fitness should hold up better", fitAfter > fitBefore * 0.7)
        assertTrue("and you should end up fresh", fitAfter - tiredAfter > 0)
    }

    @Test
    fun `gaps in the history are filled so rest is seen`() {
        val sparse = listOf(
            DayLoad(today.minusDays(4), 20.0, 30, 1),
            DayLoad(today, 20.0, 30, 1),
        )
        val filled = TrainingLoad.fill(sparse, today.minusDays(6), today)
        assertEquals(7, filled.size)
        assertEquals(5, filled.count { !it.trained })
        assertEquals(today, filled.last().date)
    }

    @Test
    fun `two sessions on one day are added together`() {
        val twice = listOf(
            DayLoad(today, 20.0, 30, 1),
            DayLoad(today, 15.0, 25, 1),
        )
        val filled = TrainingLoad.fill(twice, today, today)
        assertEquals(1, filled.size)
        assertEquals(35.0, filled[0].trimp, 1e-9)
        assertEquals(2, filled[0].sessions)
        assertEquals(55, filled[0].minutes)
    }
}
