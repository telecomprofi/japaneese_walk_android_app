package com.telecomprofi.japanesewalk.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    /** Length of the prescribed protocol. */
    val plannedSec: Int,
    /** Seconds actually walked (paused time excluded). */
    val elapsedSec: Int,
    val avgHr: Int,
    val maxHr: Int,
    val kcal: Int,
    val z1Sec: Int,
    val z2Sec: Int,
    val z3Sec: Int,
    val z4Sec: Int,
    val z5Sec: Int,
    val onTargetSec: Int,
    val offTargetSec: Int,
    val completedSets: Int,
    val plannedSets: Int,
    val completed: Boolean,
    /** Human-readable protocol, e.g. "5 x 3/3 min @ 70/40% HRR". */
    val protocolLabel: String,
    /** Legacy column from the removed scoring layer; always 0 now. */
    val xpAwarded: Int = 0,
) {
    val zoneSeconds: List<Int> get() = listOf(z1Sec, z2Sec, z3Sec, z4Sec, z5Sec)
    val adherencePercent: Int
        get() {
            val total = onTargetSec + offTargetSec
            return if (total == 0) 0 else (onTargetSec * 100) / total
        }
    val minutes: Int get() = elapsedSec / 60
}

@Entity(
    tableName = "hr_samples",
    indices = [Index("sessionId")],
)
data class HrSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tSec: Int,
    val hr: Int,
    /** Ordinal of com.telecomprofi.japanesewalk.core.Phase. */
    val phase: Int,
)

/** One audio file from the workout playlist, with its analysed tempo. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val uri: String,
    val fileName: String,
    val title: String?,
    val artist: String?,
    val durationMs: Long,
    val bpm: Double,
    val bpmConfidence: Double,
    val analyzedAt: Long,
    val playlistOrder: Int,
    val missing: Boolean = false,
) {
    val displayName: String get() = title ?: fileName.substringBeforeLast('.')
    val analysed: Boolean get() = bpm > 0.0
}

/**
 * Everything found in the scanned music folder, with its ID3 metadata, so the
 * workout list can be built by searching rather than by hunting through folders.
 * Separate from [TrackEntity]: this is the shelf, that is the playlist.
 */
@Entity(tableName = "catalogue")
data class CatalogueEntity(
    @PrimaryKey val uri: String,
    val fileName: String,
    val relPath: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val scannedAt: Long,
) {
    val displayName: String get() = title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.')
    val subtitle: String
        get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
            .ifEmpty { relPath }
}

