package com.telecomprofi.japanesewalk.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Perceived loudness, ITU-R BS.1770 / EBU R 128, on a mono signal.
 *
 * Why not simple RMS: a bass-heavy track and a bright one at the same RMS do
 * not sound equally loud, and RMS is dominated by whichever has more low end.
 * BS.1770 first passes the signal through the two "K-weighting" filters — a
 * high shelf standing in for the head's acoustic effect, then a high-pass that
 * discounts the rumble the ear barely registers — and only then measures power.
 * That is what makes two different tracks measured this way actually match when
 * they are played back at the offset it prescribes.
 *
 * The gating is the other half of the standard. Music is not continuous: a
 * fade-out or a quiet intro would drag the average down and make the loud part
 * of a quiet-average track blast. So power is measured in 400 ms blocks, blocks
 * below -70 LUFS are thrown away as silence, and then blocks more than 10 LU
 * below the remaining average are thrown away too — leaving a figure that
 * describes the body of the track rather than its quietest moments.
 *
 * Filter coefficients are derived for the actual sample rate rather than using
 * the 48 kHz table in the spec, so this measures correctly on the 44.1 kHz that
 * nearly every MP3 arrives at.
 */
object Loudness {

    /** Blocks quieter than this are silence and take no part in the average. */
    const val ABSOLUTE_GATE_LUFS = -70.0

    /** Blocks this far below the ungated average are excluded as well. */
    const val RELATIVE_GATE_LU = 10.0

    private const val BLOCK_MS = 400
    private const val STEP_MS = 100

    /**
     * Integrated loudness in LUFS, or null when there is nothing measurable —
     * too short, or silence.
     *
     * @param samples mono, nominally in [-1, 1].
     */
    fun integratedLufs(samples: FloatArray, sampleRate: Int): Double? {
        if (sampleRate <= 0) return null
        val blockLen = sampleRate * BLOCK_MS / 1000
        val step = sampleRate * STEP_MS / 1000
        if (blockLen <= 0 || step <= 0 || samples.size < blockLen) return null

        val weighted = kWeight(samples, sampleRate)

        // Mean square per overlapping block.
        val blocks = ArrayList<Double>((weighted.size - blockLen) / step + 1)
        var start = 0
        while (start + blockLen <= weighted.size) {
            var sum = 0.0
            for (i in start until start + blockLen) {
                val v = weighted[i].toDouble()
                sum += v * v
            }
            blocks += sum / blockLen
            start += step
        }
        if (blocks.isEmpty()) return null

        fun loudnessOf(meanSquare: Double): Double =
            if (meanSquare <= 0.0) Double.NEGATIVE_INFINITY else -0.691 + 10.0 * log10(meanSquare)

        val aboveAbsolute = blocks.filter { loudnessOf(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return null

        val relativeThreshold = loudnessOf(aboveAbsolute.average()) - RELATIVE_GATE_LU
        val kept = aboveAbsolute.filter { loudnessOf(it) > relativeThreshold }
        val pool = kept.ifEmpty { aboveAbsolute }

        val result = loudnessOf(pool.average())
        return if (result.isFinite()) result else null
    }

    /** Largest absolute sample, for headroom checks. */
    fun peak(samples: FloatArray): Float {
        var peak = 0f
        for (v in samples) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        return peak
    }

    /**
     * The two K-weighting stages. Coefficients follow the analogue prototypes in
     * BS.1770 mapped through the bilinear transform, which is how the 48 kHz
     * table in the spec is itself derived.
     */
    internal fun kWeight(samples: FloatArray, sampleRate: Int): FloatArray {
        val fs = sampleRate.toDouble()

        // Stage 1: high shelf, +4 dB above ~1.7 kHz.
        val f0 = 1681.9744509555319
        val gainDb = 3.99984385397
        val q1 = 0.7071752369554196
        val k1 = tan(PI * f0 / fs)
        val vh = 10.0.pow(gainDb / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val d1 = 1.0 + k1 / q1 + k1 * k1
        val s1 = Biquad(
            b0 = (vh + vb * k1 / q1 + k1 * k1) / d1,
            b1 = 2.0 * (k1 * k1 - vh) / d1,
            b2 = (vh - vb * k1 / q1 + k1 * k1) / d1,
            a1 = 2.0 * (k1 * k1 - 1.0) / d1,
            a2 = (1.0 - k1 / q1 + k1 * k1) / d1,
        )

        // Stage 2: high pass at ~38 Hz.
        val f2 = 38.13547087602444
        val q2 = 0.5003270373238773
        val k2 = tan(PI * f2 / fs)
        val d2 = 1.0 + k2 / q2 + k2 * k2
        val s2 = Biquad(
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a1 = 2.0 * (k2 * k2 - 1.0) / d2,
            a2 = (1.0 - k2 / q2 + k2 * k2) / d2,
        )

        val out = FloatArray(samples.size)
        s1.process(samples, out)
        s2.process(out, out)
        return out
    }

    private class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    ) {
        fun process(input: FloatArray, output: FloatArray) {
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (i in input.indices) {
                val x0 = input[i].toDouble()
                val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x0
                y2 = y1; y1 = y0
                output[i] = y0.toFloat()
            }
        }
    }
}

/**
 * Turns measured loudness into a playback gain.
 *
 * The first version of this levelled everything down to the **quietest** track
 * in the playlist, which matched them perfectly and made the whole app roughly
 * half as loud as any other player: on a normal set of modern masters, spread
 * over 14 dB, a typical track lost 11 dB. Matching is not worth that.
 *
 * So the reference is the playlist's **median** loudness. Half the tracks are
 * already at or below it and are left alone or lifted slightly; the loud half
 * comes down by a few dB. The typical track plays at full volume, which is what
 * makes the app sound like everything else on the phone, and the startle — which
 * was the point — is what the cap on the loud side removes.
 *
 * Gain is split in two because the two halves are applied by different things:
 *
 *  - **attenuation** is the player's own volume, which cannot exceed 1.0;
 *  - **boost** is a [android.media.audiofx.LoudnessEnhancer] on the audio
 *    session, which is the only way to get above unity at all.
 *
 * Both are capped: 8 dB down and 6 dB up by default. A cap is not a compromise
 * here but the point — an outlier should not drag the rest of the playlist with
 * it, in either direction.
 */
object LoudnessNormaliser {

    /** Stored value for "not measured yet". Real music is always well below 0. */
    const val UNMEASURED = 0.0

    const val DEFAULT_MAX_CUT_DB = 8
    const val DEFAULT_MAX_BOOST_DB = 6

    fun isMeasured(lufs: Double): Boolean = lufs < -1.0 && lufs > -80.0

    /**
     * The level the playlist will sit at: the median of everything measured, or
     * null when nothing has been. Median rather than mean so one very quiet or
     * very loud track cannot move the whole playlist.
     */
    fun reference(measured: List<Double>): Double? {
        val usable = measured.filter { isMeasured(it) }.sorted()
        if (usable.isEmpty()) return null
        // Upper middle for an even count, which biases towards the louder side
        // and so towards leaving volume alone.
        return usable[usable.size / 2]
    }

    /**
     * Total gain for one track, dB. Negative attenuates, positive boosts.
     */
    fun gainDb(
        lufs: Double,
        reference: Double?,
        maxCutDb: Int = DEFAULT_MAX_CUT_DB,
        maxBoostDb: Int = DEFAULT_MAX_BOOST_DB,
    ): Double {
        if (reference == null || !isMeasured(lufs)) return 0.0
        val cut = -maxCutDb.coerceIn(0, 24).toDouble()
        val boost = maxBoostDb.coerceIn(0, 12).toDouble()
        return (reference - lufs).coerceIn(cut, boost)
    }

    /** The part of [gainDb] the player's volume can apply: never above unity. */
    fun attenuationFactor(gainDb: Double): Float =
        10.0.pow(gainDb.coerceAtMost(0.0) / 20.0).toFloat().coerceIn(0.1f, 1.0f)

    /** The part that needs the audio-effect boost, in millibels for the platform API. */
    fun boostMillibels(gainDb: Double): Int =
        (gainDb.coerceAtLeast(0.0) * 100).toInt().coerceIn(0, 1200)
}
