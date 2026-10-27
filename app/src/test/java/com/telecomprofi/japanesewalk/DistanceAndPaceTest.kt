package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.DataMetric
import com.telecomprofi.japanesewalk.core.DistanceTracker
import com.telecomprofi.japanesewalk.core.GaugeGradient
import com.telecomprofi.japanesewalk.core.Geo
import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.Pace
import com.telecomprofi.japanesewalk.core.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceAndPaceTest {

    /** Roughly 111 m per 0.001 degree of latitude. */
    @Test
    fun `great circle distance is right to the metre`() {
        assertEquals(111.19, Geo.distanceMeters(50.0, 30.0, 50.001, 30.0), 0.5)
        assertEquals(0.0, Geo.distanceMeters(50.0, 30.0, 50.0, 30.0), 1e-9)
    }

    @Test
    fun `walking in a line accumulates distance`() {
        val tracker = DistanceTracker()
        var t = 0L
        // Ten fixes, ~11 m apart, one per second: about 110 m at 4 km/h.
        repeat(11) { i ->
            tracker.add(50.0 + i * 0.0001, 30.0, 5f, t)
            t += 10_000
        }
        assertEquals(111.0, tracker.totalMeters, 6.0)
        assertEquals(0.111, tracker.totalKm, 0.01)
    }

    @Test
    fun `standing still does not accumulate drift`() {
        val tracker = DistanceTracker()
        var t = 0L
        // Jitter of a metre or two around one spot, which is what a stationary
        // phone actually reports.
        val jitter = listOf(0.0, 0.00001, -0.00001, 0.000015, -0.000005, 0.0)
        jitter.forEach { d ->
            tracker.add(50.0 + d, 30.0 + d, 6f, t)
            t += 1_000
        }
        assertEquals(0.0, tracker.totalMeters, 1e-9)
        assertTrue(tracker.rejectedFixes > 0)
    }

    @Test
    fun `imprecise fixes and impossible jumps are rejected`() {
        val tracker = DistanceTracker()
        tracker.add(50.0, 30.0, 5f, 0)
        // 80 m accuracy: no idea where we are.
        assertEquals(0.0, tracker.add(50.001, 30.0, 80f, 1_000), 1e-9)
        // 1 km in one second: a bad fix, not a sprint.
        assertEquals(0.0, tracker.add(50.01, 30.0, 5f, 2_000), 1e-9)
        assertEquals(0.0, tracker.totalMeters, 1e-9)
    }

    @Test
    fun `speed and pace conversions`() {
        // 1.4 m/s is a typical walk: about 5 km/h, near 12 min/km.
        assertEquals(5.04, Pace.kph(1.4), 0.01)
        assertEquals(11.9, Pace.minutesPerKm(1.4)!!, 0.1)
        assertEquals("11:54", Pace.formatPace(Pace.minutesPerKm(1.4)))
        assertEquals("5.0", Pace.formatKph(1.4))
        assertEquals("1.50", Pace.formatKm(1500.0))
    }

    @Test
    fun `pace is blank rather than absurd when barely moving`() {
        assertEquals(null, Pace.minutesPerKm(0.0))
        assertEquals("--:--", Pace.formatPace(null))
        assertEquals("--:--", Pace.formatPace(Pace.minutesPerKm(0.05)))
        assertEquals(0.0, Pace.averageSpeedMps(100.0, 0), 1e-9)
        assertEquals(0.0, Pace.kcalPerMinute(100, 0), 1e-9)
    }

    @Test
    fun `average speed and burn rate`() {
        assertEquals(1.5, Pace.averageSpeedMps(1800.0, 1200), 1e-9)
        assertEquals(10.0, Pace.kcalPerMinute(300, 1800), 1e-9)
    }

    @Test
    fun `the gauge ramp is positionally honest`() {
        val bands = HrZone.bands(UserProfile(ageYears = 40, restingHr = 60))
        // At each zone's midpoint the ramp is exactly that zone's colour, so the
        // colour you see and the zone you are in always agree.
        bands.forEach { band ->
            val mid = (band.gaugeStart + band.gaugeEnd) / 2.0
            assertEquals(
                "midpoint of ${band.zone.shortLabel}",
                band.zone.argb,
                GaugeGradient.argbAt(mid, bands),
            )
        }
        // The ends are pinned rather than fading out.
        assertEquals(bands.first().zone.argb, GaugeGradient.argbAt(0.0, bands))
        assertEquals(bands.last().zone.argb, GaugeGradient.argbAt(1.0, bands))
    }

    @Test
    fun `the ramp interpolates between zones instead of stepping`() {
        val bands = HrZone.bands(UserProfile(ageYears = 40, restingHr = 60))
        val green = bands[0].zone.argb
        val yellow = bands[1].zone.argb
        val between = GaugeGradient.argbAt(
            (bands[0].gaugeEnd + bands[1].gaugeStart) / 2.0,
            bands,
        )
        assertTrue("boundary colour should differ from both anchors",
            between != green && between != yellow)
        // Still fully opaque: only the channels are blended.
        assertEquals(0xFFL, (between shr 24) and 0xFF)
    }

    @Test
    fun `the ramp is defined everywhere and clamps outside the gauge`() {
        val bands = HrZone.bands(UserProfile())
        assertEquals(GaugeGradient.argbAt(0.0, bands), GaugeGradient.argbAt(-1.0, bands))
        assertEquals(GaugeGradient.argbAt(1.0, bands), GaugeGradient.argbAt(2.0, bands))
        // No band list at all must not throw.
        assertTrue(GaugeGradient.argbAt(0.5, emptyList()) != 0L)
    }

    @Test
    fun `a low heart rate produces a visible fill on both gauges`() {
        // 74 bpm with a resting rate of 60 used to look motionless. It sits a
        // quarter of the way along, which is a quarter of the ring and a quarter
        // of the arc — both are driven by this one number.
        val profile = UserProfile(ageYears = 40, restingHr = 60)
        val fraction = HrZone.gaugeFraction(74, profile)
        assertTrue("74 bpm at $fraction", fraction > 0.20 && fraction < 0.35)
        assertEquals(HrZone.Z1, HrZone.forHr(74, profile))
    }

    @Test
    fun `metric selection round trips through preferences`() {
        val chosen = listOf(DataMetric.BPM, DataMetric.DISTANCE, DataMetric.CALORIES)
        assertEquals(chosen, DataMetric.parse(DataMetric.serialise(chosen)))
        // Unknown or empty keys fall back to the walking set rather than nothing.
        // Not DEFAULT: that now includes the trainer tiles, and a walker with no
        // trainer would get three blank squares.
        assertEquals(DataMetric.WALK_DEFAULT, DataMetric.parse(""))
        assertEquals(DataMetric.WALK_DEFAULT, DataMetric.parse("nonsense,rubbish"))
        assertEquals(listOf(DataMetric.BPM), DataMetric.parse("bpm,nonsense"))
        assertEquals(13, DataMetric.entries.size)
        assertTrue(DataMetric.DISTANCE.needsGps && !DataMetric.BPM.needsGps)
        assertTrue(DataMetric.POWER.needsTrainer && !DataMetric.BPM.needsTrainer)
    }

    @Test
    fun `each mode's default tiles are all fillable in that mode`() {
        // The point of two default sets: a tile that can never be filled in the
        // mode it is shown in reads as "you are not moving", not "not measured".
        assertEquals(9, DataMetric.WALK_DEFAULT.size)
        assertTrue(DataMetric.WALK_DEFAULT.none { it.needsTrainer })
        assertEquals(9, DataMetric.CYCLE_DEFAULT.size)
        assertTrue(DataMetric.CYCLE_DEFAULT.none { it.needsGps })
        // Both keep the four that need nothing at all.
        listOf(DataMetric.BPM, DataMetric.AVG_BPM, DataMetric.CALORIES, DataMetric.KCAL_PER_MIN)
            .forEach {
                assertTrue("$it missing from walk", it in DataMetric.WALK_DEFAULT)
                assertTrue("$it missing from cycle", it in DataMetric.CYCLE_DEFAULT)
            }
        // And every default entry survives a round trip through preferences.
        assertEquals(DataMetric.CYCLE_DEFAULT, DataMetric.parse(DataMetric.serialise(DataMetric.CYCLE_DEFAULT)))
    }
}
