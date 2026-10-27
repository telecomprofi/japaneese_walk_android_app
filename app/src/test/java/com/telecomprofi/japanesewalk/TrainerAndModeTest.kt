package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.Calories
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.UserProfile
import com.telecomprofi.japanesewalk.core.WorkoutAccumulator
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.trainer.Ftms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fitness Machine Service byte layouts, and the cycling side of the engine.
 *
 * Worth testing precisely because a mistake here does not crash: read the flags
 * field wrong and every subsequent value shifts by two bytes, producing a power
 * figure that is merely implausible rather than obviously broken.
 */
class TrainerAndModeTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `bit zero of the flags is inverted`() {
        // "More Data" clear means instantaneous speed IS present. Getting this
        // backwards is the classic FTMS bug.
        val withSpeed = Ftms.parseIndoorBikeData(
            bytes(0x44, 0x00, 0xB8, 0x0B, 0xB4, 0x00, 0x96, 0x00)
        )!!
        assertEquals(30.0, withSpeed.speedKph!!, 1e-9)
        assertEquals(90.0, withSpeed.cadenceRpm!!, 1e-9)
        assertEquals(150, withSpeed.powerW)

        val withoutSpeed = Ftms.parseIndoorBikeData(
            bytes(0x45, 0x00, 0xC8, 0x00, 0xFA, 0x00)
        )!!
        assertNull(withoutSpeed.speedKph)
        assertEquals(100.0, withoutSpeed.cadenceRpm!!, 1e-9)
        assertEquals(250, withoutSpeed.powerW)
    }

    @Test
    fun `every field of a full frame lands in the right place`() {
        // Flags 0x0FFE: speed present (bit 0 clear) plus every other field up to
        // elapsed time. If any width is wrong, one of these assertions moves.
        val frame = bytes(
            0xFE, 0x0F,             // flags
            0xC4, 0x09,             // speed 25.00 km/h
            0xD0, 0x07,             // average speed 20.00 km/h
            0xAA, 0x00,             // cadence 85 rpm (0.5 resolution)
            0xA0, 0x00,             // average cadence 80 rpm
            0x39, 0x30, 0x00,       // distance 12345 m (uint24)
            0x2A, 0x00,             // resistance 42
            0xAF, 0x00,             // power 175 W
            0xA0, 0x00,             // average power 160 W
            0x7B, 0x00, 0x90, 0x01, 0x07, // energy: 123 kcal, 400 kcal/h, 7 kcal/min
            0x84,                   // heart rate 132
            0x55,                   // metabolic equivalent 8.5
            0x58, 0x02,             // elapsed 600 s
        )
        val d = Ftms.parseIndoorBikeData(frame)!!
        assertEquals(25.0, d.speedKph!!, 1e-9)
        assertEquals(20.0, d.avgSpeedKph!!, 1e-9)
        assertEquals(85.0, d.cadenceRpm!!, 1e-9)
        assertEquals(80.0, d.avgCadenceRpm!!, 1e-9)
        assertEquals(12345, d.distanceM)
        assertEquals(42, d.resistance)
        assertEquals(175, d.powerW)
        assertEquals(160, d.avgPowerW)
        assertEquals(123, d.kcal)
        assertEquals(132, d.heartRate)
        assertEquals(600, d.elapsedSec)
    }

    @Test
    fun `power is signed, because coasting reports negative`() {
        // Flags 0x41: bit 0 set means no speed field, bit 6 means power.
        val d = Ftms.parseIndoorBikeData(bytes(0x41, 0x00, 0xEC, 0xFF))!!
        assertEquals(-20, d.powerW)
    }

    @Test
    fun `a truncated frame yields nulls rather than nonsense`() {
        // Flags claim cadence and power; only cadence arrived.
        val d = Ftms.parseIndoorBikeData(bytes(0x45, 0x00, 0xC8, 0x00))!!
        assertEquals(100.0, d.cadenceRpm!!, 1e-9)
        assertNull(d.powerW)
        assertNull(Ftms.parseIndoorBikeData(bytes(0x00)))
        assertNull(Ftms.parseIndoorBikeData(ByteArray(0)))
    }

    @Test
    fun `target power is a signed little-endian sixteen`() {
        assertArrayEquals(bytes(0x05, 0x96, 0x00), Ftms.setTargetPower(150))
        assertArrayEquals(bytes(0x05, 0xCE, 0xFF), Ftms.setTargetPower(-50))
    }

    @Test
    fun `resistance has two encodings because the spec is ambiguous`() {
        assertArrayEquals(bytes(0x04, 0x37), Ftms.setTargetResistance(55))
        // Wide form carries tenths, so 55 becomes 550 = 0x0226.
        assertArrayEquals(bytes(0x04, 0x26, 0x02), Ftms.setTargetResistance(55, wide = true))
    }

    @Test
    fun `control responses are decoded, including the ones that mean no`() {
        val ok = Ftms.parseControlResponse(bytes(0x80, 0x00, 0x01))!!
        assertEquals(Ftms.OP_REQUEST_CONTROL, ok.requestOp)
        assertTrue(ok.ok)

        val bad = Ftms.parseControlResponse(bytes(0x80, 0x04, 0x03))!!
        assertEquals(Ftms.OP_SET_RESISTANCE, bad.requestOp)
        assertEquals(Ftms.RESULT_INVALID_PARAMETER, bad.result)
        assertTrue(!bad.ok)
        assertEquals("invalid parameter", Ftms.resultText(bad.result))

        // Anything that is not a response frame is not one.
        assertNull(Ftms.parseControlResponse(bytes(0x04, 0x37)))
        assertNull(Ftms.parseControlResponse(bytes(0x80, 0x00)))
    }

    @Test
    fun `ranges and features are read from the right words`() {
        val power = Ftms.parsePowerRange(bytes(0x00, 0x00, 0xD0, 0x07, 0x01, 0x00))!!
        assertEquals(0, power.min)
        assertEquals(2000, power.max)
        assertEquals(1, power.increment)

        // Resistance arrives in tenths and is reported in whole levels.
        val resistance = Ftms.parseResistanceRange(bytes(0x00, 0x00, 0xE8, 0x03, 0x0A, 0x00))!!
        assertEquals(0, resistance.min)
        assertEquals(100, resistance.max)
        assertEquals(1, resistance.increment)

        // Target-setting features are the SECOND uint32; bit 2 resistance, bit 3 power.
        val features = Ftms.parseFeatures(
            bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0C, 0x00, 0x00, 0x00)
        )!!
        assertTrue(features.canSetResistance)
        assertTrue(features.canSetPower)

        val powerOnly = Ftms.parseFeatures(bytes(0, 0, 0, 0, 0x08, 0x00, 0x00, 0x00))!!
        assertTrue(powerOnly.canSetPower)
        assertTrue(!powerOnly.canSetResistance)
    }

    // --- the cycling side of the maths ------------------------------------

    @Test
    fun `calories come from work when there is a power meter`() {
        // 200 W for an hour is 720 kJ of work; at 22.5% gross efficiency the body
        // spends about 765 kcal to produce it.
        assertEquals(765.0, Calories.kcalFromPower(200, 3600.0), 5.0)
        assertEquals(0.0, Calories.kcalFromPower(0, 60.0), 1e-9)
        assertEquals(0.0, Calories.kcalFromPower(-30, 60.0), 1e-9)
    }

    @Test
    fun `the accumulator prefers measured power to the heart-rate model`() {
        val profile = UserProfile(ageYears = 52, restingHr = 89, weightKg = 115.0)
        val fromPower = WorkoutAccumulator(profile)
        val fromHr = WorkoutAccumulator(profile)
        repeat(60) {
            fromPower.tick(1, 120, null, null, powerW = 150)
            fromHr.tick(1, 120, null, null)
        }
        assertEquals(60, fromPower.elapsedSec)
        assertEquals(150, fromPower.avgPowerW)
        assertEquals(9.0, fromPower.workKj, 0.001)
        assertTrue(fromPower.kcal > 0.0)
        // Different models, so different answers — the point is that the power
        // one is not silently falling through to the heart-rate one.
        assertTrue(fromPower.kcal != fromHr.kcal)
    }

    @Test
    fun `work accumulates as watt-seconds`() {
        val acc = WorkoutAccumulator(UserProfile())
        acc.tick(10, null, null, null, powerW = 100)
        assertEquals(1.0, acc.workKj, 1e-9)
        acc.tick(10, null, null, null, powerW = 200)
        assertEquals(3.0, acc.workKj, 1e-9)
        assertEquals(150, acc.avgPowerW)
        assertEquals(200, acc.maxPowerW)
    }

    @Test
    fun `each mode speaks its own language`() {
        assertEquals("FAST", WorkoutMode.WALK.label(Phase.FAST))
        assertEquals("SLOW", WorkoutMode.WALK.label(Phase.SLOW))
        assertEquals("HARD", WorkoutMode.CYCLE.label(Phase.FAST))
        assertEquals("EASY", WorkoutMode.CYCLE.label(Phase.SLOW))
        assertEquals("COOL-DOWN", WorkoutMode.CYCLE.label(Phase.COOLDOWN))
        assertTrue(WorkoutMode.WALK.usesGps)
        assertTrue(!WorkoutMode.CYCLE.usesGps)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            expected.joinToString(" ") { "%02X".format(it) },
            actual.joinToString(" ") { "%02X".format(it) },
        )
    }
}
