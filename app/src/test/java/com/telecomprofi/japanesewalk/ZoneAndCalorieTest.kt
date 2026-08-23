package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.Calories
import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.TargetBand
import com.telecomprofi.japanesewalk.core.TargetVerdict
import com.telecomprofi.japanesewalk.core.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneAndCalorieTest {

    private val profile = UserProfile(ageYears = 40, restingHr = 60, weightKg = 80.0, isMale = true)

    @Test
    fun `predicted max heart rate uses Tanaka`() {
        assertEquals(180, profile.maxHr)
        assertEquals(120, profile.hrReserve)
    }

    @Test
    fun `karvonen targets for the two IWT intensities`() {
        assertEquals(144, profile.hrForReservePct(0.70))
        assertEquals(108, profile.hrForReservePct(0.40))
    }

    @Test
    fun `measured max heart rate wins when enabled and sane`() {
        val measured = profile.copy(useMeasuredMaxHr = true, measuredMaxHr = 195)
        assertEquals(195, measured.maxHr)
        val nonsense = profile.copy(useMeasuredMaxHr = true, measuredMaxHr = 65)
        assertEquals(180, nonsense.maxHr)
    }

    @Test
    fun `fast target is the top zone and slow target the bottom one`() {
        // Three zones only: green / yellow / orange, no red.
        assertEquals(3, HrZone.entries.size)
        assertEquals(HrZone.Z3, HrZone.forHr(144, profile))   // 70% HRR, fast bout
        assertEquals(HrZone.Z1, HrZone.forHr(108, profile))   // 40% HRR, slow bout
        assertEquals(HrZone.Z1, HrZone.forHr(62, profile))    // resting
        assertEquals(HrZone.Z3, HrZone.forHr(178, profile))   // near max
    }

    @Test
    fun `zone bands cover the whole gauge with no gaps`() {
        val bands = HrZone.bands(profile)
        assertEquals(3, bands.size)
        bands.zipWithNext().forEach { (a, b) -> assertEquals(a.hiBpm, b.loBpm) }
        // Zone 1 starts at the very bottom of the gauge, so there is no dim,
        // colourless lead-in for a resting heart rate to disappear into — which
        // is exactly what made the old gauge look dead below 100 bpm.
        assertEquals(0.0, bands.first().gaugeStart, 1e-9)
        assertEquals(1.0, bands.last().gaugeEnd, 1e-9)
    }

    @Test
    fun `a resting heart rate lands inside the first coloured zone`() {
        assertEquals(HrZone.Z1, HrZone.forHr(63, profile))
        val first = HrZone.bands(profile).first()
        val f = HrZone.gaugeFraction(63, profile)
        assertTrue("63 bpm at $f", f > first.gaugeStart && f < first.gaugeEnd)
    }

    @Test
    fun `gauge fraction is monotonic and clamped`() {
        assertTrue(HrZone.gaugeFraction(100, profile) < HrZone.gaugeFraction(150, profile))
        assertEquals(1.0, HrZone.gaugeFraction(250, profile), 1e-9)
    }

    @Test
    fun `the gauge moves at heart rates below the training zones`() {
        // Resting 60, max 180. A hair above resting used to pin the needle at the
        // origin, which looked like a broken gauge for the whole warm-up.
        val justAboveResting = HrZone.gaugeFraction(63, profile)
        assertTrue("63 bpm gave $justAboveResting", justAboveResting > 0.10)
        assertTrue(justAboveResting < HrZone.gaugeFraction(75, profile))
        assertTrue(HrZone.gaugeFraction(75, profile) < HrZone.gaugeFraction(100, profile))
        // Far below resting still clamps to the origin.
        assertEquals(0.0, HrZone.gaugeFraction(30, profile), 1e-9)
    }

    @Test
    fun `the whole fast range counts as on target`() {
        // 50-70% HRR on this profile is 120-144 bpm.
        val band = TargetBand(0.50, 0.70)
        assertEquals(120, band.lowBpm(profile))
        assertEquals(144, band.highBpm(profile))
        assertEquals("120-144", band.rangeText(profile))
        listOf(120, 128, 136, 144).forEach {
            assertEquals("$it should be on target", TargetVerdict.ON_TARGET, band.verdict(it, profile))
        }
        assertEquals(100, band.percentOfTarget(144, profile))
    }

    @Test
    fun `verdicts escalate outside the range, not away from its middle`() {
        val band = TargetBand(0.50, 0.70)
        assertEquals(TargetVerdict.BELOW, band.verdict(118, profile))
        assertEquals(TargetVerdict.WAY_BELOW, band.verdict(105, profile))
        assertEquals(TargetVerdict.ABOVE, band.verdict(150, profile))
        assertEquals(TargetVerdict.WAY_ABOVE, band.verdict(168, profile))
    }

    @Test
    fun `a recovery band never complains about a low heart rate`() {
        // Slow bout: ceiling only. Anything at or under the top is fine.
        val band = TargetBand(0.20, 0.40, floorMatters = false)
        listOf(60, 80, 95, 108).forEach {
            assertEquals("$it should be on target", TargetVerdict.ON_TARGET, band.verdict(it, profile))
        }
        // The ceiling still matters, so failing to recover is still flagged.
        assertEquals(TargetVerdict.ABOVE, band.verdict(115, profile))
        assertEquals(TargetVerdict.WAY_ABOVE, band.verdict(135, profile))
    }

    @Test
    fun `aim is the middle of the range`() {
        val band = TargetBand(0.50, 0.70)
        assertEquals(0.60, band.aimPct, 1e-9)
        assertEquals(132, band.aimBpm(profile))
    }

    @Test
    fun `keytel energy expenditure is plausible and never negative`() {
        val fast = Calories.kcalPerMinuteFromHr(144, profile)
        val slow = Calories.kcalPerMinuteFromHr(108, profile)
        assertTrue("fast=$fast slow=$slow", fast > slow)
        assertTrue("fast=$fast", fast in 8.0..16.0)
        assertTrue("slow=$slow", slow in 3.0..9.0)
        assertEquals(0.0, Calories.kcalPerMinuteFromHr(30, profile), 1e-9)
    }

    @Test
    fun `met fallback keeps counting when the strap drops out`() {
        assertTrue(Calories.kcalForInterval(null, Phase.FAST, 60.0, profile) > 0.0)
        assertTrue(
            Calories.kcalForInterval(null, Phase.FAST, 60.0, profile) >
                Calories.kcalForInterval(null, Phase.SLOW, 60.0, profile)
        )
    }
}
