package com.telecomprofi.japanesewalk.core

/**
 * The tiles available on the Data screen. Which of them are shown, and in what
 * order, is a user preference — hence a stable [key] to persist rather than an
 * ordinal that would shift if this list is ever reordered.
 */
enum class DataMetric(val key: String, val label: String, val unit: String) {
    BPM("bpm", "Heart rate", "bpm"),
    DURATION("duration", "Duration", ""),
    DISTANCE("distance", "Distance", "km"),
    SPEED("speed", "Speed", "kph"),
    AVG_SPEED("avg_speed", "Avg speed", "kph"),
    AVG_PACE("avg_pace", "Avg pace", "min/km"),
    AVG_BPM("avg_bpm", "Avg heart rate", "bpm"),
    CALORIES("kcal", "Calories", "kcal"),
    KCAL_PER_MIN("kcal_min", "Burn rate", "kcal/min"),
    ;

    /** True for the tiles that cannot be filled without a location fix. */
    val needsGps: Boolean
        get() = this == DISTANCE || this == SPEED || this == AVG_SPEED || this == AVG_PACE

    companion object {
        fun byKey(key: String): DataMetric? = entries.firstOrNull { it.key == key }

        /** Sensible default set, in reading order. */
        val DEFAULT: List<DataMetric> = listOf(
            BPM, DURATION, DISTANCE,
            SPEED, AVG_SPEED, AVG_PACE,
            AVG_BPM, CALORIES, KCAL_PER_MIN,
        )

        fun parse(keys: String): List<DataMetric> {
            val parsed = keys.split(',').mapNotNull { byKey(it.trim()) }
            return parsed.ifEmpty { DEFAULT }
        }

        fun serialise(metrics: List<DataMetric>): String = metrics.joinToString(",") { it.key }
    }
}
