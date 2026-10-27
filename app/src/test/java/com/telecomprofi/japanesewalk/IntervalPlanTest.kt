package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.IntensityModel
import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.TargetBand
import com.telecomprofi.japanesewalk.core.TargetVerdict
import com.telecomprofi.japanesewalk.core.UserProfile
import com.telecomprofi.japanesewalk.core.WorkoutAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalPlanTest {

    private val plan = IntervalPlan(IwtProtocol())

    @Test
    fun `default protocol is the classic thirty minute session`() {
        assertEquals(1800, plan.totalSec)
        assertEquals(10, plan.segments.size)
        assertEquals(5, plan.fastSegments)
        assertEquals(Phase.SLOW, plan.segments.first().phase)
        assertEquals("5 x 3/3 min @ 70/40% HRR", plan.protocol.label())
    }

    @Test
    fun `timeline has no gaps or overlaps`() {
        plan.segments.zipWithNext().forEach { (a, b) -> assertEquals(a.endSec, b.startSec) }
        assertEquals(plan.totalSec, plan.segments.last().endSec)
    }

    @Test
    fun `segment lookup walks the whole session`() {
        assertEquals(Phase.SLOW, plan.segmentAt(0)?.phase)
        assertEquals(1, plan.segmentAt(0)?.setNumber)
        assertEquals(Phase.FAST, plan.segmentAt(200)?.phase)
        assertEquals(5, plan.segmentAt(1799)?.setNumber)
        assertNull(plan.segmentAt(1800))
        assertTrue(plan.isFinished(1800))
        assertEquals(10, plan.remainingInSegment(170))
    }

    @Test
    fun `the last second of the plan counts towards the total`() {
        // segmentAt() is exclusive of endSec, so the final tick lands past the
        // plan. Crediting only up to that point reported a 30-minute session as
        // 29 minutes and four sets out of five.
        assertEquals(1, plan.uncreditedSeconds(1799))
        assertEquals(0, plan.uncreditedSeconds(1800))
        assertEquals(0, plan.uncreditedSeconds(1900))

        assertEquals(5, plan.completedSets(1800))
        assertEquals(4, plan.completedSets(1799))
        assertEquals(0, plan.completedSets(0))
        assertEquals(1, plan.completedSets(360))
        assertEquals(30, 1800 / 60)
    }

    @Test
    fun `warm up and cool down are added around the sets`() {
        val custom = IntervalPlan(IwtProtocol(sets = 3, warmupSec = 120, cooldownSec = 180))
        assertEquals(120 + 3 * 360 + 180, custom.totalSec)
        assertEquals(Phase.WARMUP, custom.segments.first().phase)
        assertEquals(Phase.COOLDOWN, custom.segments.last().phase)
    }

    @Test
    fun `fast first ordering is respected`() {
        assertEquals(Phase.FAST, IntervalPlan(IwtProtocol(startWithSlow = false)).segments.first().phase)
    }

    @Test
    fun `fast and slow bands come from the protocol`() {
        val profile = UserProfile(ageYears = 40, restingHr = 60, weightKg = 80.0)
        val fast = plan.protocol.targetBand(Phase.FAST, IntensityModel.HR_RESERVE)
        val slow = plan.protocol.targetBand(Phase.SLOW, IntensityModel.HR_RESERVE)
        // Defaults: 70% top with a 20-point band, so 50-70% HRR = 120-144 bpm.
        assertEquals(120, fast.lowBpm(profile))
        assertEquals(144, fast.highBpm(profile))
        assertTrue(fast.floorMatters)
        // Slow bout is a ceiling only.
        assertEquals(108, slow.highBpm(profile))
        assertTrue(!slow.floorMatters)
    }

    @Test
    fun `a narrower band width tightens the fast range`() {
        val profile = UserProfile(ageYears = 40, restingHr = 60)
        val tight = IwtProtocol(bandWidthPct = 0.08).targetBand(Phase.FAST, IntensityModel.HR_RESERVE)
        assertEquals(134, tight.lowBpm(profile))
        assertEquals(144, tight.highBpm(profile))
    }

    @Test
    fun `accumulator tracks zones adherence and calories`() {
        val profile = UserProfile(ageYears = 40, restingHr = 60, weightKg = 80.0)
        val acc = WorkoutAccumulator(profile)
        val band = plan.protocol.targetBand(Phase.FAST, IntensityModel.HR_RESERVE)
        val segment = plan.segmentAt(200)
        repeat(180) { acc.tick(1, 144, segment, band) }

        assertEquals(180, acc.elapsedSec)
        assertEquals(144, acc.avgHr)
        assertEquals(144, acc.maxHr)
        assertEquals(180, acc.secondsInZone[2])   // 144 bpm is the top zone
        assertEquals(100, acc.adherencePercent)
        assertTrue("kcal=${acc.kcalRounded()}", acc.kcalRounded() in 20..60)

        repeat(60) { acc.tick(1, 100, segment, band) }
        assertEquals(75, acc.adherencePercent)
        assertEquals(TargetVerdict.WAY_BELOW, acc.streakVerdict)
        assertEquals(60, acc.streakSec)
    }

    @Test
    fun `a completed session always reports the whole plan`() {
        // The tick that notices the end can land on any of these seconds
        // depending on scheduling jitter, and a pause or a skip shifts it again.
        // None of that may change what a completed 30-minute walk reports.
        listOf(1795, 1798, 1799, 1800, 1803).forEach { creditedWhenFinishRuns ->
            val acc = WorkoutAccumulator(UserProfile())
            acc.skipAhead(creditedWhenFinishRuns)

            // This is what finish() now does for a completed session.
            val tail = plan.uncreditedSeconds(acc.elapsedSec)
            if (tail > 0) acc.skipAhead(tail)

            val minutes = acc.elapsedSec / 60
            assertTrue(
                "finishing at $creditedWhenFinishRuns s reported $minutes min",
                minutes >= 30,
            )
            assertEquals(5, plan.completedSets(acc.elapsedSec))
        }
    }

    @Test
    fun `an early stop is still reported honestly`() {
        // Stopping short is not "completed", so nothing is topped up.
        val acc = WorkoutAccumulator(UserProfile())
        acc.skipAhead(1200)
        assertEquals(20, acc.elapsedSec / 60)
        assertEquals(3, plan.completedSets(acc.elapsedSec))
        assertTrue(!plan.isFinished(acc.elapsedSec))
    }

    @Test
    fun `skipped time is not credited as work`() {
        val acc = WorkoutAccumulator(UserProfile())
        acc.tick(1, 130, plan.segmentAt(200), plan.protocol.targetBand(Phase.FAST, IntensityModel.HR_RESERVE))
        val kcalBefore = acc.kcal
        acc.skipAhead(120)
        assertEquals(121, acc.elapsedSec)
        assertEquals(kcalBefore, acc.kcal, 1e-9)
    }
}
