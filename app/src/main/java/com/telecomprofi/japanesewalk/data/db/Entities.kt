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
    /** Ordinal of com.telecomprofi.japanesewalk.core.WorkoutMode; 0 is walking. */
    val mode: Int = 0,
) {
    val zoneSeconds: List<Int> get() = listOf(z1Sec, z2Sec, z3Sec, z4Sec, z5Sec)
    val adherencePercent: Int
        get() {
            val total = onTargetSec + offTargetSec
            return if (total == 0) 0 else (onTargetSec * 100) / total
        }
    val minutes: Int get() = elapsedSec / 60

    /**
     * The zone the session actually lived in — the one with the most seconds in
     * it, not an average of zone numbers, which would land between two zones and
     * describe neither. z4 and z5 are legacy columns from a five-zone scheme and
     * fold into the top zone.
     */
    val dominantZone: com.telecomprofi.japanesewalk.core.HrZone
        get() {
            val folded = listOf(z1Sec, z2Sec, z3Sec + z4Sec + z5Sec)
            val best = folded.indices.maxByOrNull { folded[it] } ?: 0
            return com.telecomprofi.japanesewalk.core.HrZone.entries[best]
        }

    /** True when nothing was ever recorded, so the card can say so plainly. */
    val hasZoneData: Boolean get() = (z1Sec + z2Sec + z3Sec + z4Sec + z5Sec) > 0

    val workoutMode: com.telecomprofi.japanesewalk.core.WorkoutMode
        get() = com.telecomprofi.japanesewalk.core.WorkoutMode.entries
            .getOrElse(mode) { com.telecomprofi.japanesewalk.core.WorkoutMode.WALK }
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
/** Where a track's tempo came from. */
object BpmSource {
    const val MEASURED = 0
    const val TAG = 1
    const val MANUAL = 2
}

/** Below this, a measured tempo is kept but not used for matching. */
const val MIN_TRUSTED_CONFIDENCE = 0.35

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
    /**
     * Integrated loudness in LUFS, measured from the same decoded excerpt as the
     * tempo. 0.0 means not measured — real music is always well below zero.
     */
    val loudnessLufs: Double = 0.0,
    /**
     * Where [bpm] came from: 0 measured from the audio, 1 read from the file's
     * own tag, 2 typed in by hand. It matters because a measured tempo can be
     * wrong in a way the other two cannot, so only a measured one has to earn
     * its place with a confidence score.
     */
    val bpmSource: Int = BpmSource.MEASURED,
) {
    val displayName: String get() = title ?: fileName.substringBeforeLast('.')
    val analysed: Boolean get() = bpm > 0.0

    /**
     * Whether this tempo is trustworthy enough to match a bout to.
     *
     * A tag or a typed figure is taken at its word. A measured one has to clear
     * a confidence bar — below it the track still plays, it just no longer
     * claims to be 103 BPM when it is 155, which is how a fast song ended up in
     * the slow half of a walk.
     */
    val tempoTrusted: Boolean
        get() = bpm > 0.0 && (bpmSource != BpmSource.MEASURED || bpmConfidence >= MIN_TRUSTED_CONFIDENCE)

    val bpmSourceLabel: String
        get() = when (bpmSource) {
            BpmSource.TAG -> "from the file's tag"
            BpmSource.MANUAL -> "set by hand"
            else -> "measured"
        }
    val loudnessMeasured: Boolean
        get() = com.telecomprofi.japanesewalk.core.LoudnessNormaliser.isMeasured(loudnessLufs)
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

