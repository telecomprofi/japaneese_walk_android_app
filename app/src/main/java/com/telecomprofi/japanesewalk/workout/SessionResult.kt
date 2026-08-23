package com.telecomprofi.japanesewalk.workout

import com.telecomprofi.japanesewalk.data.db.HrSampleEntity

data class SessionResult(
    val startedAt: Long,
    val endedAt: Long,
    val plannedSec: Int,
    val elapsedSec: Int,
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
    val samples: List<HrSampleEntity>,
) {
    val minutes: Int get() = elapsedSec / 60
    val adherencePercent: Int
        get() {
            val total = onTargetSec + offTargetSec
            return if (total == 0) 0 else (onTargetSec * 100) / total
        }
}
