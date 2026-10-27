package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.Loudness
import com.telecomprofi.japanesewalk.core.LoudnessNormaliser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Loudness measurement and levelling.
 *
 * The absolute LUFS figure matters less than the *relative* one: normalisation
 * only needs two tracks measured the same way to differ by the right number of
 * decibels. The tests are written around that, plus the gating rules that stop a
 * quiet intro from dragging a track's figure down.
 */
class LoudnessTest {

    private val sr = 44_100

    private fun sine(amplitude: Double, seconds: Double = 3.0, freq: Double = 1000.0): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n) { i -> (amplitude * sin(2 * PI * freq * i / sr)).toFloat() }
    }

    @Test
    fun `halving amplitude costs six decibels`() {
        val loud = Loudness.integratedLufs(sine(0.5), sr)!!
        val quiet = Loudness.integratedLufs(sine(0.25), sr)!!
        assertEquals(6.02, loud - quiet, 0.15)
    }

    @Test
    fun `a full-scale tone measures near minus three LUFS`() {
        // A 1 kHz sine at full scale has mean square 0.5, so -0.691 + 10log10(0.5)
        // = -3.7 before weighting; K-weighting is close to flat at 1 kHz.
        val lufs = Loudness.integratedLufs(sine(1.0), sr)!!
        assertTrue("got $lufs", lufs > -6.0 && lufs < -0.5)
    }

    @Test
    fun `silence is not measurable`() {
        assertNull(Loudness.integratedLufs(FloatArray(sr * 2), sr))
    }

    @Test
    fun `too short to gate is not measurable`() {
        assertNull(Loudness.integratedLufs(sine(0.5, seconds = 0.2), sr))
        assertNull(Loudness.integratedLufs(FloatArray(0), sr))
    }

    @Test
    fun `the relative gate ignores a quiet passage`() {
        // Three seconds of music then three of near-silence. An ungated average
        // would land halfway between; the gate should keep it near the loud part,
        // which is what stops a track with a long fade-out playing back too loud.
        val loud = sine(0.5, seconds = 3.0)
        val nearSilence = sine(0.0005, seconds = 3.0)
        val mixed = loud + nearSilence
        val gated = Loudness.integratedLufs(mixed, sr)!!
        val loudOnly = Loudness.integratedLufs(loud, sr)!!
        assertEquals(loudOnly, gated, 1.0)
    }

    @Test
    fun `low frequencies count for less than mid ones`() {
        // The K-weighting high-pass is the whole reason RMS is not good enough:
        // 40 Hz at the same amplitude as 1 kHz is perceived as much quieter.
        val mid = Loudness.integratedLufs(sine(0.5, freq = 1000.0), sr)!!
        val bass = Loudness.integratedLufs(sine(0.5, freq = 40.0), sr)!!
        assertTrue("bass $bass should measure below mid $mid", bass < mid - 3.0)
    }

    @Test
    fun `peak finds the largest excursion`() {
        assertEquals(0.5f, Loudness.peak(sine(0.5)), 0.01f)
        assertEquals(0f, Loudness.peak(FloatArray(100)), 0f)
    }

    // --- levelling ---------------------------------------------------------

    /** A realistic set of modern masters: 14 dB from the loudest to the quietest. */
    private val playlist = listOf(
        -6.5, -7.2, -8.0, -8.4, -9.1, -9.6, -10.2, -11.0, -11.8, -13.4, -16.0, -20.5,
    )

    @Test
    fun `the typical track is not touched at all`() {
        // This is the regression guard. The first version of this levelled the
        // playlist to its *quietest* track, which cost a typical track 11 dB and
        // made the app audibly half as loud as every other player on the phone.
        val ref = LoudnessNormaliser.reference(playlist)!!
        assertEquals(-9.6, ref, 1e-9)
        assertEquals(0.0, LoudnessNormaliser.gainDb(ref, ref), 1e-9)
        assertEquals(1.0f, LoudnessNormaliser.attenuationFactor(0.0), 1e-6f)
    }

    @Test
    fun `the reference is the median, not the extreme`() {
        val base = LoudnessNormaliser.reference(playlist)!!
        assertEquals(-9.6, base, 1e-9)

        // A pathological outlier barely moves it. Not "not at all" — adding a
        // thirteenth track to twelve shifts the middle by half a step whatever
        // its value — but the point is the scale of the shift. The design this
        // replaced used the quietest track as the reference, where the same
        // outlier moved it 19.5 dB and took the whole playlist's volume with it.
        val withVeryQuiet = LoudnessNormaliser.reference(playlist + (-40.0))!!
        assertEquals(-10.2, withVeryQuiet, 1e-9)
        assertTrue("moved ${base - withVeryQuiet}", kotlin.math.abs(base - withVeryQuiet) < 1.0)
        assertTrue(kotlin.math.abs(base - playlist.min()) > 10.0)

        val withVeryLoud = LoudnessNormaliser.reference(playlist + (-2.0))!!
        assertTrue(kotlin.math.abs(base - withVeryLoud) < 1.0)

        // Unmeasured entries are not data and must not count towards the middle.
        assertEquals(base, LoudnessNormaliser.reference(playlist + List(20) { 0.0 })!!, 1e-9)
    }

    @Test
    fun `loud tracks come down and quiet ones go up`() {
        val ref = LoudnessNormaliser.reference(playlist)!!
        assertEquals(-3.1, LoudnessNormaliser.gainDb(playlist.first(), ref), 1e-9)
        assertEquals(3.8, LoudnessNormaliser.gainDb(-13.4, ref), 1e-9)
        // And the whole playlist ends up inside a far narrower window.
        val played = playlist.map { it + LoudnessNormaliser.gainDb(it, ref) }
        assertTrue("spread ${played.max() - played.min()}", played.max() - played.min() < 6.0)
    }

    @Test
    fun `both caps hold`() {
        val ref = -10.0
        assertEquals(-8.0, LoudnessNormaliser.gainDb(-1.5, ref), 1e-9)
        assertEquals(6.0, LoudnessNormaliser.gainDb(-40.0, ref), 1e-9)
        assertEquals(-3.0, LoudnessNormaliser.gainDb(-1.5, ref, maxCutDb = 3), 1e-9)
        assertEquals(2.0, LoudnessNormaliser.gainDb(-40.0, ref, maxBoostDb = 2), 1e-9)
        // Zero on both is levelling switched off.
        assertEquals(0.0, LoudnessNormaliser.gainDb(-1.5, ref, 0, 0), 1e-9)
        assertEquals(0.0, LoudnessNormaliser.gainDb(-40.0, ref, 0, 0), 1e-9)
    }

    @Test
    fun `the gain is split between the volume and the effect`() {
        // Attenuation is the player's volume, which cannot exceed unity; boost is
        // the audio effect, which is the only thing that can.
        assertEquals(1.0f, LoudnessNormaliser.attenuationFactor(6.0), 1e-6f)
        assertEquals(0, LoudnessNormaliser.boostMillibels(-6.0))
        assertEquals(0.5012f, LoudnessNormaliser.attenuationFactor(-6.0), 1e-3f)
        assertEquals(600, LoudnessNormaliser.boostMillibels(6.0))
        // Neither half may run away.
        assertTrue(LoudnessNormaliser.attenuationFactor(-60.0) >= 0.1f)
        assertTrue(LoudnessNormaliser.boostMillibels(90.0) <= 1200)
    }

    @Test
    fun `nothing measured means nothing changed`() {
        assertNull(LoudnessNormaliser.reference(listOf(0.0, 0.0)))
        assertNull(LoudnessNormaliser.reference(emptyList()))
        assertEquals(0.0, LoudnessNormaliser.gainDb(-9.0, null), 1e-9)
        // An unmeasured track inside a measured playlist is also left alone.
        assertEquals(0.0, LoudnessNormaliser.gainDb(0.0, -10.0), 1e-9)
        assertTrue(!LoudnessNormaliser.isMeasured(LoudnessNormaliser.UNMEASURED))
    }

    @Test
    fun `a single track playlist is its own reference`() {
        val ref = LoudnessNormaliser.reference(listOf(-9.0))!!
        assertEquals(-9.0, ref, 1e-9)
        assertEquals(0.0, LoudnessNormaliser.gainDb(-9.0, ref), 1e-9)
    }
}
