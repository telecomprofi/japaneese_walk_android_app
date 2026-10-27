package com.telecomprofi.japanesewalk.data.repo

import com.telecomprofi.japanesewalk.data.db.AppDatabase
import com.telecomprofi.japanesewalk.data.db.SessionEntity
import com.telecomprofi.japanesewalk.workout.SessionResult

data class SaveOutcome(
    val sessionId: Long,
    val week: WeekStats,
    val saved: Boolean,
)

/**
 * Write side of the local store. A session shorter than a minute is treated as
 * a false start and discarded rather than polluting the weekly total.
 */
class WorkoutRepository(
    private val db: AppDatabase,
    private val stats: StatsRepository,
) {
    private val sessions = db.sessionDao()

    suspend fun save(result: SessionResult, minimumSeconds: Int = 60): SaveOutcome {
        if (result.elapsedSec < minimumSeconds) {
            return SaveOutcome(0, stats.buildWeekStats(), saved = false)
        }

        val zones = result.zoneSeconds
        val id = sessions.insert(
            SessionEntity(
                startedAt = result.startedAt,
                endedAt = result.endedAt,
                plannedSec = result.plannedSec,
                elapsedSec = result.elapsedSec,
                avgHr = result.avgHr,
                maxHr = result.maxHr,
                kcal = result.kcal,
                z1Sec = zones.getOrElse(0) { 0 },
                z2Sec = zones.getOrElse(1) { 0 },
                z3Sec = zones.getOrElse(2) { 0 },
                z4Sec = zones.getOrElse(3) { 0 },
                z5Sec = zones.getOrElse(4) { 0 },
                onTargetSec = result.onTargetSec,
                offTargetSec = result.offTargetSec,
                completedSets = result.completedSets,
                plannedSets = result.plannedSets,
                completed = result.completed,
                protocolLabel = result.protocolLabel,
                mode = result.mode.ordinal,
            )
        )
        if (result.samples.isNotEmpty()) {
            sessions.insertSamples(result.samples.map { it.copy(sessionId = id) })
        }
        return SaveOutcome(id, stats.buildWeekStats(), saved = true)
    }

    suspend fun delete(sessionId: Long) {
        sessions.deleteSamples(sessionId)
        sessions.deleteSession(sessionId)
    }
}
