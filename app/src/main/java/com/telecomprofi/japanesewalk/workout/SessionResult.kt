package com.telecomprofi.japanesewalk.workout

import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.data.db.HrSampleEntity

data class SessionResult(
    val startedAt: Long,
    val endedAt: Long,
    val plannedSec: Int,
    val elapsedSec: Int,
    /** Of [elapsedSec], how much was tracked after the plan itself finished. */
    val coolDownSec: Int = 0,
    val avgHr: Int,
    val maxHr: Int,
    val kcal: Int,
    val zoneSeconds: List<Int>,
    val onTargetSec: Int,
    val offTargetSec: Int,
    val completedSets: Int,
    val plannedSets: Int,
    val completed: Boolean,
    val protocolLabel: String,
    val mode: WorkoutMode = WorkoutMode.WALK,
    val samples: List<HrSampleEntity>,
) {
    val minutes: Int get() = elapsedSec / 60

    /** Minutes of the plan, excluding the cool-down tail. */
    val planMinutes: Int get() = ((elapsedSec - coolDownSec).coerceAtLeast(0)) / 60
    val coolDownMinutes: Int get() = coolDownSec / 60
    val adherencePercent: Int
        get() {
            val total = onTargetSec + offTargetSec
            return if (total == 0) 0 else (onTargetSec * 100) / total
        }
}
