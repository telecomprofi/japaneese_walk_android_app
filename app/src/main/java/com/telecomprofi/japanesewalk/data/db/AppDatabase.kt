package com.telecomprofi.japanesewalk.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        HrSampleEntity::class,
        TrackEntity::class,
        CatalogueEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun trackDao(): TrackDao
    abstract fun catalogueDao(): CatalogueDao

    companion object {

        /**
         * v2 adds the searchable catalogue and cleans up the duplicate rows the
         * old deep folder scan created: the same song synced into more than one
         * place produced several document URIs, and a primary key on the URI
         * cannot see that. Keeps the copy with the shortest URI, which is the one
         * in the shallowest folder.
         *
         * Written as a real migration rather than a destructive fallback so that
         * workout history survives the upgrade.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `catalogue` (" +
                        "`uri` TEXT NOT NULL, `fileName` TEXT NOT NULL, `relPath` TEXT NOT NULL, " +
                        "`title` TEXT, `artist` TEXT, `album` TEXT, " +
                        "`durationMs` INTEGER NOT NULL, `scannedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uri`))"
                )
                db.execSQL(
                    "DELETE FROM tracks WHERE uri NOT IN (" +
                        "SELECT uri FROM (SELECT uri, MIN(LENGTH(uri)) FROM tracks " +
                        "GROUP BY LOWER(fileName), CASE WHEN durationMs > 0 THEN durationMs / 1000 ELSE 0 END))"
                )
            }
        }

        /** v3 drops the achievements table along with the scoring layer. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `achievements`")
            }
        }

        /**
         * Someone upgrading straight from v1 needs the catalogue created and the
         * achievements table gone, so the two steps run in sequence.
         */
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "japanese_walk.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
