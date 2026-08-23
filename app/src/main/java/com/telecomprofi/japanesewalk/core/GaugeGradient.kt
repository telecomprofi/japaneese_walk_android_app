package com.telecomprofi.japanesewalk.core

/** A colour anchored at a position along the gauge, 0..1. */
data class GaugeStop(val position: Double, val argb: Long)

/**
 * The colour of the gauge at any point along it.
 *
 * Anchoring each zone's colour at that zone's **midpoint** and interpolating
 * between the anchors gives a smooth green-to-orange ramp that is still
 * positionally honest: the colour at a given point is the colour of the zone
 * that point belongs to, so how far the fill has travelled and what colour it
 * has reached always agree.
 *
 * Colours are handled as packed ARGB longs rather than a UI type, so the ramp
 * can be tested without a renderer, and so the gauge and the arc cannot drift
 * apart — they both read from here.
 */
object GaugeGradient {

    fun stops(bands: List<ZoneBand>): List<GaugeStop> {
        if (bands.isEmpty()) return emptyList()
        return buildList {
            // Pin the ends so the first and last zones do not fade off.
            add(GaugeStop(0.0, bands.first().zone.argb))
            bands.forEach { band ->
                add(GaugeStop((band.gaugeStart + band.gaugeEnd) / 2.0, band.zone.argb))
            }
            add(GaugeStop(1.0, bands.last().zone.argb))
        }.distinctBy { it.position }.sortedBy { it.position }
    }

    /** Interpolated colour at [position] along the gauge. */
    fun argbAt(position: Double, bands: List<ZoneBand>): Long {
        val stops = stops(bands)
        if (stops.isEmpty()) return 0xFF888888
        val p = position.coerceIn(0.0, 1.0)
        val upperIndex = stops.indexOfFirst { it.position >= p }
        if (upperIndex <= 0) return stops.first().argb
        val lower = stops[upperIndex - 1]
        val upper = stops[upperIndex]
        val span = upper.position - lower.position
        if (span <= 0.0) return upper.argb
        return lerpArgb(lower.argb, upper.argb, (p - lower.position) / span)
    }

    private fun lerpArgb(from: Long, to: Long, t: Double): Long {
        val f = t.coerceIn(0.0, 1.0)
        fun channel(shift: Int): Long {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * f).toLong().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
