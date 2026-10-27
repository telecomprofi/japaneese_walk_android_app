package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.IntensityModel
import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.MaxHrFormula
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.TargetVerdict
import com.telecomprofi.japanesewalk.core.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The %HRmax zone model and the ramped target band.
 *
 * The reference case throughout is the real profile that prompted both: 52
 * years old, resting 89, watch-style zones. 220 − 52 = 168, so zone 3 must
 * start at 0.70 × 168 ≈ 118 — which is where a Galaxy Watch puts it, and what
 * the Karvonen model was getting wrong.
 */
class ZoneModelAndRampTest {

    private val watchProfile = UserProfile(
        ageYears = 52,
        restingHr = 89,
        weightKg = 115.0,
        intensityModel = IntensityModel.MAX_HR,
        maxHrFormula = MaxHrFormula.CLASSIC_220,
    )

    @Test
    fun `220 minus age drives max heart rate`() {
        assertEquals(168, watchProfile.maxHr)
        assertEquals(172, watchProfile.copy(maxHrFormula = MaxHrFormula.TANAKA).maxHr)
    }

    @Test
    fun `zone three starts where the watch says it does`() {
        val bands = HrZone.bands(watchProfile)
        assertEquals(3, bands.size)
        // Z1 tops out at 60% of 168, Z3 opens at 70%.
        assertEquals(101, bands[0].hiBpm)
        assertEquals(101, bands[1].loBpm)
        assertEquals(118, bands[1].hiBpm)
        assertEquals(118, bands[2].loBpm)
        assertEquals(HrZone.Z3, HrZone.forHr(122, watchProfile))
        assertEquals(HrZone.Z2, HrZone.forHr(110, watchProfile))
        assertEquals(HrZone.Z1, HrZone.forHr(95, watchProfile))
    }

    @Test
    fun `the two models disagree, which is why they are exclusive`() {
        val karvonen = watchProfile.copy(intensityModel = IntensityModel.HR_RESERVE)
        // Same heart rate, same person, two zones apart. On the watch scale
        // 122 of 168 is 73% and firmly Z3. On the reserve scale it is only
        // 33 of 79 points = 42%, below the 45% Z2 line at 125 bpm — which is
        // exactly the mismatch that made the Karvonen zones feel wrong.
        assertEquals(HrZone.Z3, HrZone.forHr(122, watchProfile))
        assertEquals(HrZone.Z1, HrZone.forHr(122, karvonen))
        assertEquals(125, karvonen.hrForReservePct(0.45))
    }

    @Test
    fun `targets follow the model so colours and coaching agree`() {
        val protocol = IwtProtocol()
        val fast = protocol.targetBand(Phase.FAST, IntensityModel.MAX_HR)
        val slow = protocol.targetBand(Phase.SLOW, IntensityModel.MAX_HR)
        // 68-80% and 48-60% of 168.
        assertEquals(114, fast.lowBpm(watchProfile))
        assertEquals(134, fast.highBpm(watchProfile))
        assertEquals(101, slow.highBpm(watchProfile))
        // The fast bout sits inside the orange band and the slow bout at the
        // green/yellow line — the whole point of switching the targets too.
        assertEquals(HrZone.Z3, HrZone.forHr(fast.highBpm(watchProfile), watchProfile))
        assertTrue(HrZone.forHr(slow.highBpm(watchProfile), watchProfile).idx <= 2)
    }

    @Test
    fun `gauge floor keeps a resting heart rate on the dial`() {
        // 89 of 168 is 53%, comfortably above the 35% floor, so the needle moves.
        val f = HrZone.gaugeFraction(89, watchProfile)
        assertTrue("resting should not pin the needle", f > 0.05)
        assertTrue(f < 0.5)
        assertTrue(HrZone.gaugeFraction(89, watchProfile) < HrZone.gaugeFraction(130, watchProfile))
        assertEquals(1.0, HrZone.gaugeFraction(200, watchProfile), 1e-9)
    }

    // --- the ramp ---------------------------------------------------------

    private val plan = IntervalPlan(IwtProtocol(transitionSec = 60), IntensityModel.HR_RESERVE)
    private val profile = UserProfile(ageYears = 40, restingHr = 60)

    @Test
    fun `band at the start of a fast bout is still the slow band`() {
        // Default protocol starts slow, so the first fast bout opens at 180 s.
        val atStart = plan.bandAt(180)
        val slow = plan.bandFor(Phase.SLOW)
        assertNotNull(atStart)
        assertEquals(slow.highPct, atStart!!.highPct, 1e-9)
    }

    @Test
    fun `band has fully arrived once the transition is over`() {
        val settled = plan.bandAt(180 + 60)
        val fast = plan.bandFor(Phase.FAST)
        assertEquals(fast.highPct, settled!!.highPct, 1e-9)
        assertEquals(fast.lowPct, settled.lowPct, 1e-9)
    }

    @Test
    fun `band is strictly between the two halfway through the transition`() {
        val mid = plan.bandAt(180 + 30)!!
        val slow = plan.bandFor(Phase.SLOW)
        val fast = plan.bandFor(Phase.FAST)
        assertTrue(mid.highPct > slow.highPct)
        assertTrue(mid.highPct < fast.highPct)
        assertEquals((slow.highPct + fast.highPct) / 2, mid.highPct, 1e-9)
    }

    @Test
    fun `ramp stops the coach shouting at a heart still on its way up`() {
        // 110 bpm ten seconds into a fast bout: well under the settled 108-144
        // band's floor, but not under the ramped one, because the heart has
        // barely had time to respond.
        val settled = plan.bandFor(Phase.FAST)
        assertEquals(TargetVerdict.WAY_BELOW, settled.verdict(100, profile))
        val ramped = plan.bandAt(190)!!
        assertEquals(TargetVerdict.ON_TARGET, ramped.verdict(100, profile))
    }

    @Test
    fun `dropping into a slow bout is not scolded either`() {
        // First slow bout of set two starts at 360 s. A heart rate still up at
        // the fast ceiling is expected there, not "still above".
        val ramped = plan.bandAt(365)!!
        assertEquals(TargetVerdict.ON_TARGET, ramped.verdict(140, profile))
        // By the end of the transition the ceiling is the slow one again.
        assertEquals(TargetVerdict.WAY_ABOVE, plan.bandAt(360 + 60)!!.verdict(150, profile))
    }

    @Test
    fun `zero transition restores hard steps`() {
        val square = IntervalPlan(IwtProtocol(transitionSec = 0), IntensityModel.HR_RESERVE)
        assertEquals(square.bandFor(Phase.FAST).highPct, square.bandAt(180)!!.highPct, 1e-9)
    }

    @Test
    fun `ramp never outlasts the bout it is ramping into`() {
        // One-minute bouts with a 60 s transition: without clamping, the band
        // would reach 59/60 of the way and the real target would never once be
        // applied — and the next bout would then start lerping down from a
        // ceiling that had never been in force, stepping it *up* as you ease off.
        val short = IntervalPlan(
            IwtProtocol(slowSec = 60, fastSec = 60, transitionSec = 60),
            IntensityModel.HR_RESERVE,
        )
        val fast = short.bandFor(Phase.FAST)
        // Fast bout runs 60..120; the band must have arrived before it ends.
        assertEquals(fast.highPct, short.bandAt(119)!!.highPct, 1e-9)
        assertTrue(short.bandAt(90)!!.highPct < fast.highPct)
    }

    @Test
    fun `cool-down floor is not zero bpm on the max-heart-rate scale`() {
        val cool = IwtProtocol(cooldownSec = 120).targetBand(Phase.COOLDOWN, IntensityModel.MAX_HR)
        assertTrue("0.0 of max HR is 0 bpm", cool.lowBpm(watchProfile) > 40)
        assertEquals(101, cool.highBpm(watchProfile))
        // And the reserve scale is unchanged: 0.0 there is resting heart rate.
        val reserve = IwtProtocol(cooldownSec = 120).targetBand(Phase.COOLDOWN, IntensityModel.HR_RESERVE)
        assertEquals(profile.restingHr, reserve.lowBpm(profile))
    }

    @Test
    fun `elapsed inside the current bout is what the set line shows`() {
        assertEquals(0, plan.elapsedInSegment(180))
        assertEquals(45, plan.elapsedInSegment(225))
        assertEquals(0, plan.elapsedInSegment(360))
        assertEquals(0, plan.elapsedInSegment(9_999))
    }
}
