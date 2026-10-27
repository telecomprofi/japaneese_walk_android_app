package com.telecomprofi.japanesewalk.trainer

/**
 * The Bluetooth SIG **Fitness Machine Service** (0x1826), by hand.
 *
 * Everything in this file is pure byte-twiddling with no Android in it, so the
 * field layouts — which are the part that silently produces plausible nonsense
 * when you get them wrong — can be unit-tested against known frames.
 *
 * Two characteristics matter:
 *
 *  - **Indoor Bike Data** 0x2AD2, notified several times a second, carrying
 *    speed, cadence, power and resistance behind a variable-length flags field.
 *  - **Fitness Machine Control Point** 0x2AD9, written to and indicated back,
 *    which is how the trainer's resistance is actually set.
 *
 * The awkward corner of the spec is the first flag: bit 0 is "More Data", and it
 * is **inverted** — 0 means instantaneous speed *is* present. Reading it the
 * obvious way shifts every subsequent field by two bytes and yields a cadence
 * that looks almost believable.
 */
object Ftms {

    // --- characteristic UUIDs (16-bit, expanded to the SIG base) ---
    const val SERVICE = "00001826-0000-1000-8000-00805f9b34fb"
    const val INDOOR_BIKE_DATA = "00002ad2-0000-1000-8000-00805f9b34fb"
    const val CONTROL_POINT = "00002ad9-0000-1000-8000-00805f9b34fb"
    const val MACHINE_STATUS = "00002ada-0000-1000-8000-00805f9b34fb"
    const val MACHINE_FEATURE = "00002acc-0000-1000-8000-00805f9b34fb"
    const val RESISTANCE_RANGE = "00002ad6-0000-1000-8000-00805f9b34fb"
    const val POWER_RANGE = "00002ad8-0000-1000-8000-00805f9b34fb"

    // --- control point op codes ---
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_RESISTANCE = 0x04
    const val OP_SET_POWER = 0x05
    const val OP_START_RESUME = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val RESPONSE_CODE = 0x80

    // --- result codes ---
    const val RESULT_SUCCESS = 0x01
    const val RESULT_NOT_SUPPORTED = 0x02
    const val RESULT_INVALID_PARAMETER = 0x03
    const val RESULT_FAILED = 0x04
    const val RESULT_NOT_PERMITTED = 0x05

    fun resultText(code: Int): String = when (code) {
        RESULT_SUCCESS -> "success"
        RESULT_NOT_SUPPORTED -> "op code not supported"
        RESULT_INVALID_PARAMETER -> "invalid parameter"
        RESULT_FAILED -> "operation failed"
        RESULT_NOT_PERMITTED -> "control not permitted"
        else -> "unknown result $code"
    }

    // ---------------------------------------------------------------- writes

    fun requestControl(): ByteArray = byteArrayOf(OP_REQUEST_CONTROL.toByte())

    fun startOrResume(): ByteArray = byteArrayOf(OP_START_RESUME.toByte())

    /** 1 = stop, 2 = pause. */
    fun stop(pause: Boolean = false): ByteArray =
        byteArrayOf(OP_STOP_PAUSE.toByte(), if (pause) 0x02 else 0x01)

    /** Target power in watts, SINT16 little-endian. This is ERG mode. */
    fun setTargetPower(watts: Int): ByteArray {
        val w = watts.coerceIn(-32768, 32767)
        return byteArrayOf(OP_SET_POWER.toByte(), (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte())
    }

    /**
     * Target resistance level.
     *
     * The spec is genuinely ambiguous here — the parameter is documented as a
     * single unsigned byte, while the Supported Resistance Level Range
     * characteristic that describes its bounds is SINT16 with a resolution of
     * 0.1. Trainers differ. Both encodings are offered so the client can send
     * one, watch for `invalid parameter`, and switch.
     */
    fun setTargetResistance(level: Int, wide: Boolean = false): ByteArray {
        return if (wide) {
            val v = (level * 10).coerceIn(-32768, 32767)
            byteArrayOf(OP_SET_RESISTANCE.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        } else {
            byteArrayOf(OP_SET_RESISTANCE.toByte(), level.coerceIn(0, 255).toByte())
        }
    }

    // ----------------------------------------------------------------- reads

    data class ControlResponse(val requestOp: Int, val result: Int) {
        val ok: Boolean get() = result == RESULT_SUCCESS
    }

    fun parseControlResponse(value: ByteArray): ControlResponse? {
        if (value.size < 3) return null
        if ((value[0].toInt() and 0xFF) != RESPONSE_CODE) return null
        return ControlResponse(value[1].toInt() and 0xFF, value[2].toInt() and 0xFF)
    }

    data class Ranges(val min: Int, val max: Int, val increment: Int)

    /** Supported Power Range: three 16-bit values, watts. */
    fun parsePowerRange(value: ByteArray): Ranges? {
        if (value.size < 6) return null
        return Ranges(sint16(value, 0), sint16(value, 2), uint16(value, 4).coerceAtLeast(1))
    }

    /** Supported Resistance Level Range, returned in whole levels (0.1 units in the frame). */
    fun parseResistanceRange(value: ByteArray): Ranges? {
        if (value.size < 6) return null
        return Ranges(sint16(value, 0) / 10, sint16(value, 2) / 10, (uint16(value, 4) / 10).coerceAtLeast(1))
    }

    data class Features(val canSetResistance: Boolean, val canSetPower: Boolean)

    /**
     * Fitness Machine Feature: two 32-bit fields. The second is the one that
     * says what may be *set*; bit 2 is resistance, bit 3 is power.
     */
    fun parseFeatures(value: ByteArray): Features? {
        if (value.size < 8) return null
        val target = uint32(value, 4)
        return Features(
            canSetResistance = target and (1L shl 2) != 0L,
            canSetPower = target and (1L shl 3) != 0L,
        )
    }

    /** One Indoor Bike Data notification. Absent fields stay null. */
    data class BikeData(
        val speedKph: Double? = null,
        val avgSpeedKph: Double? = null,
        val cadenceRpm: Double? = null,
        val avgCadenceRpm: Double? = null,
        val distanceM: Int? = null,
        val resistance: Int? = null,
        val powerW: Int? = null,
        val avgPowerW: Int? = null,
        val kcal: Int? = null,
        val heartRate: Int? = null,
        val elapsedSec: Int? = null,
    )

    fun parseIndoorBikeData(value: ByteArray): BikeData? {
        if (value.size < 2) return null
        val flags = uint16(value, 0)
        var i = 2

        fun needs(bytes: Int): Boolean = value.size >= i + bytes

        // Bit 0 is "More Data" and is inverted: 0 means the speed IS here.
        val speed = if (flags and 0x0001 == 0) {
            if (!needs(2)) return null
            (uint16(value, i) / 100.0).also { i += 2 }
        } else {
            null
        }
        val avgSpeed = if (flags and 0x0002 != 0 && needs(2)) {
            (uint16(value, i) / 100.0).also { i += 2 }
        } else {
            null
        }
        val cadence = if (flags and 0x0004 != 0 && needs(2)) {
            (uint16(value, i) / 2.0).also { i += 2 }
        } else {
            null
        }
        val avgCadence = if (flags and 0x0008 != 0 && needs(2)) {
            (uint16(value, i) / 2.0).also { i += 2 }
        } else {
            null
        }
        val distance = if (flags and 0x0010 != 0 && needs(3)) {
            uint24(value, i).also { i += 3 }
        } else {
            null
        }
        val resistance = if (flags and 0x0020 != 0 && needs(2)) {
            sint16(value, i).also { i += 2 }
        } else {
            null
        }
        val power = if (flags and 0x0040 != 0 && needs(2)) {
            sint16(value, i).also { i += 2 }
        } else {
            null
        }
        val avgPower = if (flags and 0x0080 != 0 && needs(2)) {
            sint16(value, i).also { i += 2 }
        } else {
            null
        }
        // Expended energy is three fields at once: total kcal, kcal/h, kcal/min.
        val kcal = if (flags and 0x0100 != 0 && needs(5)) {
            uint16(value, i).also { i += 5 }
        } else {
            null
        }
        val hr = if (flags and 0x0200 != 0 && needs(1)) {
            (value[i].toInt() and 0xFF).also { i += 1 }
        } else {
            null
        }
        if (flags and 0x0400 != 0 && needs(1)) i += 1 // metabolic equivalent
        val elapsed = if (flags and 0x0800 != 0 && needs(2)) {
            uint16(value, i).also { i += 2 }
        } else {
            null
        }

        return BikeData(
            speedKph = speed,
            avgSpeedKph = avgSpeed,
            cadenceRpm = cadence,
            avgCadenceRpm = avgCadence,
            distanceM = distance,
            resistance = resistance,
            powerW = power,
            avgPowerW = avgPower,
            kcal = kcal,
            heartRate = hr,
            elapsedSec = elapsed,
        )
    }

    // ------------------------------------------------------------- primitives

    private fun uint16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun sint16(b: ByteArray, at: Int): Int {
        val v = uint16(b, at)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun uint24(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16)

    private fun uint32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)
}
