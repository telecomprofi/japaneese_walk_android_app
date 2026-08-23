package com.telecomprofi.japanesewalk.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioDecoder"

/**
 * Decodes a slice of an audio file to mono PCM for tempo analysis.
 *
 * Uses the platform MediaCodec, so every format the phone can play (MP3, AAC,
 * FLAC, OGG ...) can be analysed without bundling a decoder.
 */
object AudioDecoder {

    data class Decoded(val mono: FloatArray, val sampleRate: Int, val durationUs: Long)

    /**
     * @param startFraction where in the track to begin (0.25 skips intros).
     * @param maxSeconds how much audio to decode; 45 s is plenty for tempo.
     */
    fun decodeMonoExcerpt(
        context: Context,
        uri: Uri,
        startFraction: Double = 0.25,
        maxSeconds: Double = 45.0,
    ): Decoded? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                if (pfd == null) return null
                extractor = MediaExtractor().apply { setDataSource(pfd.fileDescriptor) }
                val ex = extractor ?: return null

                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return null
                ex.selectTrack(trackIndex)

                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                if (durationUs > 4_000_000L) {
                    ex.seekTo((durationUs * startFraction).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }

                val mime = format.getString(MediaFormat.KEY_MIME)!!
                codec = MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
                val dec = codec ?: return null

                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    2
                }
                var pcmEncoding = 2 // AudioFormat.ENCODING_PCM_16BIT

                val out = FloatArrayBuilder(initial = 1 shl 18)
                val info = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var guard = 0

                while (!sawOutputEos && guard++ < 400_000) {
                    if (!sawInputEos) {
                        val inIndex = dec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = dec.getInputBuffer(inIndex)
                            val size = if (buffer == null) -1 else ex.readSampleData(buffer, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                dec.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }

                    when (val outIndex = dec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = dec.outputFormat
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> {
                            if (outIndex >= 0) {
                                val buf = dec.getOutputBuffer(outIndex)
                                if (buf != null && info.size > 0) {
                                    buf.position(info.offset)
                                    buf.limit(info.offset + info.size)
                                    appendMono(buf, channels, pcmEncoding, out)
                                }
                                dec.releaseOutputBuffer(outIndex, false)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        }
                    }

                    if (out.size >= (maxSeconds * sampleRate).toInt()) break
                }

                if (out.size < sampleRate) return null
                val native = out.toArray()
                val mono = TempoEstimator.resampleMono(native, sampleRate)
                return Decoded(mono, TempoEstimator.ANALYSIS_SR, durationUs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode failed for $uri: ${e.message}")
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun appendMono(buf: ByteBuffer, channels: Int, pcmEncoding: Int, out: FloatArrayBuilder) {
        buf.order(ByteOrder.nativeOrder())
        val ch = channels.coerceAtLeast(1)
        when (pcmEncoding) {
            4 -> { // AudioFormat.ENCODING_PCM_FLOAT
                val fb = buf.asFloatBuffer()
                val frames = fb.remaining() / ch
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += fb.get(i * ch + c)
                    out.add(sum / ch)
                }
            }
            3 -> { // ENCODING_PCM_8BIT (unsigned)
                val frames = buf.remaining() / ch
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += ((buf.get().toInt() and 0xFF) - 128) / 128f
                    out.add(sum / ch)
                }
            }
            else -> { // ENCODING_PCM_16BIT
                val sb = buf.asShortBuffer()
                val frames = sb.remaining() / ch
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += sb.get(i * ch + c) / 32768f
                    out.add(sum / ch)
                }
            }
        }
    }
}

/** Growable float buffer; avoids boxing a few million samples into a List. */
internal class FloatArrayBuilder(initial: Int = 4096) {
    private var data = FloatArray(initial)
    var size: Int = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
