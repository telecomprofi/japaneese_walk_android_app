package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.ReminderTime
import com.telecomprofi.japanesewalk.core.SessionDot
import com.telecomprofi.japanesewalk.core.SessionDots
import com.telecomprofi.japanesewalk.core.TimeMath
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.game.ReminderCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SessionAndReminderTest {

    @Test
    fun `session dots show the week at a glance`() {
        // 150 minutes of 30-minute walks is five circles.
        val fresh = SessionDots.of(completed = 0, partial = 0, targetMinutes = 150, sessionMinutes = 30)
        assertEquals(5, fresh.size)
        assertTrue(fresh.all { it == SessionDot.PLANNED })

        val midWeek = SessionDots.of(completed = 2, partial = 1, targetMinutes = 150, sessionMinutes = 30)
        assertEquals(
            listOf(SessionDot.DONE, SessionDot.DONE, SessionDot.PARTIAL, SessionDot.PLANNED, SessionDot.PLANNED),
            midWeek,
        )

        // Doing more than planned shows up rather than being hidden.
        val overachiever = SessionDots.of(completed = 7, partial = 0, targetMinutes = 150, sessionMinutes = 30)
        assertEquals(7, overachiever.size)
        assertTrue(overachiever.all { it == SessionDot.DONE })
    }

    @Test
    fun `session dots stay bounded and survive silly inputs`() {
        assertEquals(SessionDots.MAX, SessionDots.of(99, 0, 150, 30).size)
        assertTrue(SessionDots.of(0, 0, 150, 0).isEmpty())
        assertTrue(SessionDots.of(-3, -2, 150, 30).all { it == SessionDot.PLANNED })
    }

    @Test
    fun `a shorter session length means more circles`() {
        assertEquals(7, SessionDots.of(0, 0, 150, 20).size) // 8 planned, capped at 7
        assertEquals(3, SessionDots.of(0, 0, 150, 50).size)
    }

    @Test
    fun `reminder states the position without scoring it`() {
        val behind = ReminderCopy.forWeek(30, 150, 2, 30, walkedToday = false)
        assertTrue(behind.title.contains("30 of 150"))
        assertTrue(behind.body, behind.body.contains("120 min left"))

        val onTrack = ReminderCopy.forWeek(60, 150, 5, 30, walkedToday = false)
        assertTrue(onTrack.body, onTrack.body.contains("3 walks left"))
        assertTrue(onTrack.title.contains("40%"))

        val lastDay = ReminderCopy.forWeek(120, 150, 1, 30, walkedToday = false)
        assertTrue(lastDay.body, lastDay.body.contains("Last day"))

        val today = ReminderCopy.forWeek(120, 150, 4, 30, walkedToday = true)
        assertTrue(today.body, today.body.contains("One more walk"))
    }

    @Test
    fun `reminder is factual once the target is met`() {
        val done = ReminderCopy.forWeek(160, 150, 3, 30, walkedToday = true)
        assertTrue(done.body, done.body.contains("Weekly target reached"))
        // No streaks, no congratulation, no exclamation marks.
        assertTrue(done.body, !done.body.contains("!"))
        assertTrue(done.title.contains("160 of 150"))
    }

    @Test
    fun `weeks start on monday in the local zone`() {
        val zone = ZoneId.of("Europe/Kiev")
        val wednesday = LocalDateTime.of(2026, 8, 19, 14, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-08-17", TimeMath.localDate(TimeMath.startOfWeekMillis(wednesday, zone), zone).toString())
        assertEquals(5, TimeMath.daysLeftInWeek(wednesday, zone))

        val sunday = LocalDateTime.of(2026, 8, 23, 23, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-08-17", TimeMath.localDate(TimeMath.startOfWeekMillis(sunday, zone), zone).toString())
        assertEquals(1, TimeMath.daysLeftInWeek(sunday, zone))
    }

    @Test
    fun `months are bounded correctly`() {
        val zone = ZoneId.of("Europe/Kiev")
        val midAugust = LocalDateTime.of(2026, 8, 19, 14, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-08-01", TimeMath.localDate(TimeMath.startOfMonthMillis(midAugust, zone), zone).toString())
        assertEquals("2026-09-01", TimeMath.localDate(TimeMath.endOfMonthMillis(midAugust, zone), zone).toString())
    }

    @Test
    fun `reminder fires at the next occurrence of the chosen time`() {
        val morning = LocalDateTime.of(2026, 8, 23, 8, 0)
        // 10:30 today is still ahead: 150 minutes away.
        assertEquals(150L, ReminderTime.initialDelayMinutes(10, 30, morning))
        // 07:00 has passed, so tomorrow: 23 hours.
        assertEquals(23L * 60, ReminderTime.initialDelayMinutes(7, 0, morning))
        // Five-minute granularity carries through.
        assertEquals(35L, ReminderTime.initialDelayMinutes(8, 35, morning))
    }

    @Test
    fun `reminder delay is never zero and inputs are clamped`() {
        val now = LocalDateTime.of(2026, 8, 23, 8, 0)
        // Exactly now would be a zero delay, which WorkManager reads as "run now".
        assertTrue(ReminderTime.initialDelayMinutes(8, 0, now) >= 1)
        assertEquals(
            ReminderTime.initialDelayMinutes(23, 59, now),
            ReminderTime.initialDelayMinutes(99, 99, now),
        )
        assertEquals("10:05", ReminderTime.format(10, 5))
        assertEquals("00:00", ReminderTime.format(-3, -7))
    }

    @Test
    fun `duration formatting`() {
        assertEquals("3:05", TimeMath.mmss(185))
        assertEquals("01:02:05", TimeMath.hhmmss(3725))
        assertEquals("2h 30m", TimeMath.humanMinutes(9000))
    }

    @Test
    fun `completed circles carry the kind of session that filled them`() {
        val circles = SessionDots.withModes(
            completedModes = listOf(WorkoutMode.WALK, WorkoutMode.CYCLE, WorkoutMode.WALK),
            partial = 1,
            targetMinutes = 150,
            sessionMinutes = 30,
        )
        assertEquals(5, circles.size)
        assertEquals(
            listOf(
                SessionDot.DONE, SessionDot.DONE, SessionDot.DONE,
                SessionDot.PARTIAL, SessionDot.PLANNED,
            ),
            circles.map { it.state },
        )
        // In the order the sessions happened, so the row reads like the week did.
        assertEquals(
            listOf(WorkoutMode.WALK, WorkoutMode.CYCLE, WorkoutMode.WALK),
            circles.filter { it.state == SessionDot.DONE }.map { it.mode },
        )
        // Nothing is claimed about a circle that has not been earned yet.
        assertTrue(circles.filter { it.state != SessionDot.DONE }.all { it.mode == null })
    }

    @Test
    fun `a week of walks looks exactly as it did before rides existed`() {
        val circles = SessionDots.withModes(List(3) { WorkoutMode.WALK }, 0, 150, 30)
        assertEquals(SessionDots.of(3, 0, 150, 30), circles.map { it.state })
        assertTrue(circles.take(3).all { it.mode == WorkoutMode.WALK })
    }

    @Test
    fun `past the cap the circles shown are still labelled`() {
        val modes = List(9) { if (it % 2 == 0) WorkoutMode.WALK else WorkoutMode.CYCLE }
        val circles = SessionDots.withModes(modes, 0, 150, 30)
        assertEquals(SessionDots.MAX, circles.size)
        assertTrue(circles.all { it.state == SessionDot.DONE })
        assertTrue(circles.none { it.mode == null })
        assertEquals(modes.take(SessionDots.MAX), circles.map { it.mode })
    }

    @Test
    fun `no completed sessions means no labels to apply`() {
        val circles = SessionDots.withModes(emptyList(), 0, 150, 30)
        assertEquals(5, circles.size)
        assertTrue(circles.all { it.state == SessionDot.PLANNED && it.mode == null })
    }
}
