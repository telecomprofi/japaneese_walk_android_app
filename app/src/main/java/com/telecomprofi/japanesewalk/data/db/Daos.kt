package com.telecomprofi.japanesewalk.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class PeriodStats(
    val totalSec: Int,
    val kcal: Int,
    val sessions: Int,
    val avgHr: Double,
    val maxHr: Int,
    val onTargetSec: Int,
    val offTargetSec: Int,
) {
    companion object { val EMPTY = PeriodStats(0, 0, 0, 0.0, 0, 0, 0) }
}

data class ZoneTotals(val z1: Int, val z2: Int, val z3: Int, val z4: Int, val z5: Int) {
    val asList: List<Int> get() = listOf(z1, z2, z3, z4, z5)
    companion object { val EMPTY = ZoneTotals(0, 0, 0, 0, 0) }
}

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Insert
    suspend fun insertSamples(samples: List<HrSampleEntity>)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int = 60): Flow<List<SessionEntity>>

    /** The session just finished, for the card at the top of the Summary tab. */
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 1")
    fun latest(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt ASC")
    fun between(from: Long, to: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt ASC")
    suspend fun betweenOnce(from: Long, to: Long): List<SessionEntity>

    @Query(
        """
        SELECT COALESCE(SUM(elapsedSec), 0) AS totalSec,
               COALESCE(SUM(kcal), 0) AS kcal,
               COUNT(*) AS sessions,
               COALESCE(AVG(avgHr), 0.0) AS avgHr,
               COALESCE(MAX(maxHr), 0) AS maxHr,
               COALESCE(SUM(onTargetSec), 0) AS onTargetSec,
               COALESCE(SUM(offTargetSec), 0) AS offTargetSec
        FROM sessions WHERE startedAt >= :from AND startedAt < :to
        """
    )
    fun statsBetween(from: Long, to: Long): Flow<PeriodStats>

    @Query(
        """
        SELECT COALESCE(SUM(elapsedSec), 0) AS totalSec,
               COALESCE(SUM(kcal), 0) AS kcal,
               COUNT(*) AS sessions,
               COALESCE(AVG(avgHr), 0.0) AS avgHr,
               COALESCE(MAX(maxHr), 0) AS maxHr,
               COALESCE(SUM(onTargetSec), 0) AS onTargetSec,
               COALESCE(SUM(offTargetSec), 0) AS offTargetSec
        FROM sessions WHERE startedAt >= :from AND startedAt < :to
        """
    )
    suspend fun statsBetweenOnce(from: Long, to: Long): PeriodStats

    /** Modes of the week's completed sessions, oldest first, for the circles. */
    @Query(
        "SELECT mode FROM sessions WHERE startedAt >= :from AND startedAt < :to " +
            "AND completed = 1 ORDER BY startedAt ASC"
    )
    suspend fun completedModesBetween(from: Long, to: Long): List<Int>

    @Query("SELECT COUNT(*) FROM sessions WHERE startedAt >= :from AND startedAt < :to AND completed = 1")
    suspend fun completedBetween(from: Long, to: Long): Int

    @Query("SELECT COALESCE(SUM(elapsedSec), 0) FROM sessions")
    fun totalSeconds(): Flow<Int>

    @Query("SELECT COALESCE(SUM(kcal), 0) FROM sessions")
    fun totalKcal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sessions")
    fun sessionCount(): Flow<Int>

    @Query("SELECT MIN(startedAt) FROM sessions")
    suspend fun firstSessionAt(): Long?

    @Query(
        """
        SELECT COALESCE(SUM(z1Sec), 0) AS z1, COALESCE(SUM(z2Sec), 0) AS z2,
               COALESCE(SUM(z3Sec), 0) AS z3, COALESCE(SUM(z4Sec), 0) AS z4,
               COALESCE(SUM(z5Sec), 0) AS z5
        FROM sessions
        """
    )
    fun zoneTotals(): Flow<ZoneTotals>

    @Query("SELECT COUNT(*) FROM sessions WHERE completed = 1")
    suspend fun completedCount(): Int

    @Query("SELECT COALESCE(MAX(z4Sec), 0) FROM sessions")
    suspend fun maxZone4Sec(): Int

    @Query(
        """
        SELECT COALESCE(MAX(CASE WHEN (onTargetSec + offTargetSec) > 0
                                 THEN (onTargetSec * 100) / (onTargetSec + offTargetSec)
                                 ELSE 0 END), 0)
        FROM sessions
        """
    )
    suspend fun bestAdherencePercent(): Int

    @Query("SELECT startedAt FROM sessions")
    suspend fun allStartTimes(): List<Long>

    @Query("SELECT * FROM hr_samples WHERE sessionId = :sessionId ORDER BY tSec ASC")
    fun samplesFor(sessionId: Long): Flow<List<HrSampleEntity>>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM hr_samples WHERE sessionId = :id")
    suspend fun deleteSamples(id: Long)
}

@Dao
interface TrackDao {

    @Upsert
    suspend fun upsert(tracks: List<TrackEntity>)

    @Upsert
    suspend fun upsert(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE missing = 0 ORDER BY playlistOrder ASC")
    fun all(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0 ORDER BY playlistOrder ASC")
    suspend fun allOnce(): List<TrackEntity>

    /** Never measured (analyzedAt = 0). A failed attempt is stamped, so it is skipped. */
    @Query("SELECT * FROM tracks WHERE bpm <= 0 AND analyzedAt = 0 AND missing = 0 ORDER BY playlistOrder ASC")
    suspend fun unanalysed(): List<TrackEntity>

    /**
     * Anything still missing a measurement of either kind. Tracks queued before
     * loudness existed have a tempo and a stamp but no loudness, and they still
     * need one pass — the decode produces both figures at once.
     */
    @Query(
        "SELECT * FROM tracks WHERE missing = 0 AND " +
            "((bpm <= 0 AND analyzedAt = 0) OR loudnessLufs = 0) ORDER BY playlistOrder ASC"
    )
    suspend fun pendingAnalysis(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE missing = 0")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE bpm > 0 AND missing = 0")
    fun analysedCount(): Flow<Int>

    @Query("UPDATE tracks SET bpm = :bpm, bpmConfidence = :conf, analyzedAt = :at WHERE uri = :uri")
    suspend fun setBpm(uri: String, bpm: Double, conf: Double, at: Long)

    @Query(
        "UPDATE tracks SET bpm = :bpm, bpmConfidence = :conf, loudnessLufs = :lufs, " +
            "bpmSource = :source, analyzedAt = :at WHERE uri = :uri"
    )
    suspend fun setAnalysis(
        uri: String,
        bpm: Double,
        conf: Double,
        lufs: Double,
        at: Long,
        source: Int = BpmSource.MEASURED,
    )

    /** A tempo typed in by hand, which outranks anything measured or tagged. */
    @Query(
        "UPDATE tracks SET bpm = :bpm, bpmConfidence = 1.0, bpmSource = :source WHERE uri = :uri"
    )
    suspend fun setManualBpm(uri: String, bpm: Double, source: Int = BpmSource.MANUAL)

    @Query("UPDATE tracks SET bpm = 0, bpmConfidence = 0, loudnessLufs = 0, bpmSource = 0, analyzedAt = 0")
    suspend fun clearAnalysis()

    @Query("SELECT COUNT(*) FROM tracks WHERE loudnessLufs < -1 AND missing = 0")
    fun levelledCount(): Flow<Int>

    @Query("UPDATE tracks SET missing = :missing WHERE uri = :uri")
    suspend fun setMissing(uri: String, missing: Boolean)

    @Query("SELECT COALESCE(MAX(playlistOrder), -1) FROM tracks")
    suspend fun maxOrder(): Int

    @Query("DELETE FROM tracks WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM tracks")
    suspend fun clear()
}

@Dao
interface CatalogueDao {

    @Upsert
    suspend fun upsert(items: List<CatalogueEntity>)

    /**
     * Free-text search across the ID3 tags and the file name. SQLite's LIKE is
     * case-insensitive for ASCII, which is what people type. An empty query
     * lists everything, capped so the UI stays responsive on a big card.
     */
    @Query(
        """
        SELECT * FROM catalogue
        WHERE :query = ''
           OR title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
           OR fileName LIKE '%' || :query || '%'
        ORDER BY
            CASE WHEN artist IS NULL OR artist = '' THEN 1 ELSE 0 END,
            artist COLLATE NOCASE,
            album COLLATE NOCASE,
            title COLLATE NOCASE,
            fileName COLLATE NOCASE
        LIMIT :limit
        """
    )
    fun search(query: String, limit: Int): Flow<List<CatalogueEntity>>

    @Query("SELECT COUNT(*) FROM catalogue")
    fun count(): Flow<Int>

    @Query("SELECT * FROM catalogue WHERE uri IN (:uris)")
    suspend fun byUris(uris: List<String>): List<CatalogueEntity>

    @Query("DELETE FROM catalogue")
    suspend fun clear()
}

