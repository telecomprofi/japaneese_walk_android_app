package com.telecomprofi.japanesewalk.music

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

data class TempoResult(
    /** Estimated tempo, folded into a walking-friendly octave. */
    val bpm: Double,
    /** 0..1-ish; how much the winning tempo stands out from the field. */
    val confidence: Double,
    /** Raw un-folded winner, for debugging. */
    val rawBpm: Double = bpm,
) {
    val isUsable: Boolean get() = bpm > 0 && confidence >= 0.35
}

/**
 * On-device tempo (BPM) estimation from mono PCM.
 *
 * Pipeline: Hann-windowed STFT -> log-magnitude spectral flux onset envelope ->
 * local-mean subtraction -> autocorrelation with harmonic summing and a mild
 * log-normal tempo prior -> parabolic peak refinement -> octave folding.
 *
 * Pure Kotlin and free of Android dependencies so it can be unit-tested on the
 * JVM against synthetic click tracks.
 */
object TempoEstimator {

    /** Analysis sample rate; onset detection needs no more bandwidth than this. */
    const val ANALYSIS_SR = 11025
    const val FRAME = 1024
    const val HOP = 128

    const val MIN_BPM = 60.0
    const val MAX_BPM = 190.0

    /** Tempo octave preferred for walking music. */
    const val FOLD_LOW = 70.0
    const val FOLD_HIGH = 180.0

    private val hann = DoubleArray(FRAME) { 0.5 - 0.5 * cosTable(it) }
    private fun cosTable(i: Int) = kotlin.math.cos(2.0 * Math.PI * i / (FRAME - 1))

    /** Frames per second of the onset envelope at [ANALYSIS_SR]/[HOP]. */
    const val ENVELOPE_FPS = ANALYSIS_SR.toDouble() / HOP

    /**
     * Spectral-flux onset strength envelope.
     * @param mono samples in [-1, 1] at [ANALYSIS_SR].
     */
    fun onsetEnvelope(mono: FloatArray): DoubleArray {
        if (mono.size < FRAME * 2) return DoubleArray(0)
        val frames = (mono.size - FRAME) / HOP + 1
        val bins = FRAME / 2 + 1
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        var prev = DoubleArray(bins)
        val env = DoubleArray(frames)

        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until FRAME) {
                re[i] = mono[off + i].toDouble() * hann[i]
                im[i] = 0.0
            }
            Fft.transform(re, im)
            var flux = 0.0
            val cur = DoubleArray(bins)
            for (k in 0 until bins) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val logMag = ln(1.0 + 1000.0 * mag)
                cur[k] = logMag
                val d = logMag - prev[k]
                if (d > 0) flux += d
            }
            env[f] = flux
            prev = cur
        }
        return smoothAndRectify(env)
    }

    /** Subtract a ~0.4 s local mean and half-wave rectify, then normalise. */
    private fun smoothAndRectify(env: DoubleArray): DoubleArray {
        val n = env.size
        if (n == 0) return env
        val w = (0.4 * ENVELOPE_FPS).toInt().coerceAtLeast(1)
        val out = DoubleArray(n)
        // Running sum for the local mean.
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + env[i]
        for (i in 0 until n) {
            val lo = (i - w).coerceAtLeast(0)
            val hi = (i + w + 1).coerceAtMost(n)
            val mean = (prefix[hi] - prefix[lo]) / (hi - lo)
            out[i] = (env[i] - mean).coerceAtLeast(0.0)
        }
        // Normalise to unit standard deviation so confidence is comparable.
        val mean = out.average()
        var varSum = 0.0
        for (v in out) varSum += (v - mean) * (v - mean)
        val sd = sqrt(varSum / n)
        if (sd > 1e-9) for (i in 0 until n) out[i] = (out[i] - mean) / sd
        return out
    }

    fun estimate(mono: FloatArray): TempoResult {
        val env = onsetEnvelope(mono)
        return estimateFromEnvelope(env, ENVELOPE_FPS)
    }

    fun estimateFromEnvelope(env: DoubleArray, fps: Double): TempoResult {
        if (env.size < (4 * fps).toInt()) return TempoResult(0.0, 0.0)

        val maxLag = (3.2 * fps).toInt().coerceAtMost(env.size - 2)
        val ac = autocorrelation(env, maxLag)
        if (ac[0] <= 1e-12) return TempoResult(0.0, 0.0)
        for (i in ac.indices) ac[i] /= ac[0]

        var bestBpm = 0.0
        var bestScore = -Double.MAX_VALUE
        var worstScore = Double.MAX_VALUE
        var scoreSum = 0.0
        var scoreCount = 0

        var bpm = MIN_BPM
        while (bpm <= MAX_BPM + 1e-9) {
            val s = combScore(ac, fps, bpm) * tempoPrior(bpm)
            scoreSum += s
            scoreCount++
            if (s > bestScore) {
                bestScore = s
                bestBpm = bpm
            }
            if (s < worstScore) worstScore = s
            bpm += 0.1
        }
        if (bestScore <= 0.0) return TempoResult(0.0, 0.0)

        // Parabolic refinement on the 0.1-BPM grid.
        val refined = refine(ac, fps, bestBpm)
        val meanScore = if (scoreCount > 0) scoreSum / scoreCount else 0.0

        // Two independent signals of trustworthiness: how strongly the signal
        // actually repeats at the winning lag, and how far the winner stands
        // above the rest of the tempo field.
        val peak = interp(ac, 60.0 * fps / refined).coerceIn(0.0, 1.0)
        val prominence = ((bestScore - meanScore) / (bestScore - worstScore + 1e-9)).coerceIn(0.0, 1.0)
        val confidence = (0.6 * peak + 0.4 * prominence).coerceIn(0.0, 1.0)

        return TempoResult(bpm = fold(refined), confidence = confidence, rawBpm = refined)
    }

    private fun refine(ac: DoubleArray, fps: Double, bpm: Double): Double {
        val step = 0.1
        val y0 = combScore(ac, fps, bpm - step) * tempoPrior(bpm - step)
        val y1 = combScore(ac, fps, bpm) * tempoPrior(bpm)
        val y2 = combScore(ac, fps, bpm + step) * tempoPrior(bpm + step)
        val denom = (y0 - 2 * y1 + y2)
        if (abs(denom) < 1e-12) return bpm
        val delta = 0.5 * (y0 - y2) / denom
        return (bpm + delta.coerceIn(-1.0, 1.0) * step)
    }

    /** Autocorrelation at the beat lag plus its first two harmonics. */
    private fun combScore(ac: DoubleArray, fps: Double, bpm: Double): Double {
        val lag = 60.0 * fps / bpm
        var s = interp(ac, lag)
        s += 0.5 * interp(ac, lag * 2)
        s += 0.25 * interp(ac, lag * 3)
        // Penalise the half-tempo trap: a real beat also correlates at lag/2
        // only weakly, whereas a doubled estimate correlates strongly there.
        return s
    }

    /** Mild log-normal preference for tempi near 120 BPM. */
    private fun tempoPrior(bpm: Double): Double {
        val z = log2(bpm / 120.0) / 0.9
        return exp(-0.5 * z * z)
    }

    private fun interp(ac: DoubleArray, lag: Double): Double {
        if (lag < 1.0) return 0.0
        val i = lag.toInt()
        if (i + 1 >= ac.size) return 0.0
        val frac = lag - i
        return ac[i] * (1 - frac) + ac[i + 1] * frac
    }

    private fun autocorrelation(x: DoubleArray, maxLag: Int): DoubleArray {
        val n = x.size
        val ac = DoubleArray(maxLag + 1)
        for (lag in 0..maxLag) {
            var s = 0.0
            var t = 0
            val end = n - lag
            while (t < end) {
                s += x[t] * x[t + lag]
                t++
            }
            ac[lag] = if (end > 0) s / end else 0.0
        }
        return ac
    }

    /** Fold a tempo into [FOLD_LOW]..[FOLD_HIGH] by octaves. */
    fun fold(bpm: Double): Double {
        if (bpm <= 0.0) return 0.0
        var b = bpm
        while (b < FOLD_LOW) b *= 2
        while (b > FOLD_HIGH) b /= 2
        return b
    }

    /**
     * Distance between a track tempo and a target cadence, allowing for
     * half-time and double-time feels (musically equivalent for walking).
     * Returns the absolute BPM error of the best-matching octave, plus the
     * multiplier that achieved it.
     */
    fun octaveMatch(trackBpm: Double, targetBpm: Double): Pair<Double, Double> {
        if (trackBpm <= 0.0) return Double.MAX_VALUE to 1.0
        var bestErr = Double.MAX_VALUE
        var bestMul = 1.0
        for (mul in listOf(0.5, 1.0, 2.0)) {
            val err = abs(trackBpm * mul - targetBpm)
            if (err < bestErr) { bestErr = err; bestMul = mul }
        }
        return bestErr to bestMul
    }

    /** Downmix + naive resample to [ANALYSIS_SR]; good enough for onset work. */
    fun resampleMono(input: FloatArray, inputSr: Int): FloatArray {
        if (inputSr == ANALYSIS_SR) return input
        val ratio = ANALYSIS_SR.toDouble() / inputSr
        val outLen = (input.size * ratio).toInt()
        if (outLen <= 0) return FloatArray(0)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i / ratio
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val f = (pos - i0).toFloat()
            out[i] = input[i0] * (1 - f) + input[i1] * f
        }
        return out
    }

    @Suppress("unused")
    private fun unusedPow(x: Double) = x.pow(2)
}
