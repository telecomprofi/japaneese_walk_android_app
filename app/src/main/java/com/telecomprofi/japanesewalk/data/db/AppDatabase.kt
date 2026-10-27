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
    version = 6,
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
         * v4 records each track's measured loudness so playback can level the
         * playlist. 0.0 is the "not measured yet" marker, which is why the
         * default is not a plausible LUFS value.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `loudnessLufs` REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 records which kind of session each row was, so the week's circles
         * can show a walker or a bicycle. Everything already in the table was a
         * walk, which is what the default expresses.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `mode` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 0 = measured, which is what every existing row is.
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `bpmSource` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Someone upgrading straight from v1 needs the catalogue created, the
         * achievements table gone, the loudness column and the session mode, so
         * the steps run in sequence.
         */
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "japanese_walk.db",
            )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                )
                .build()
                .also { instance = it }
        }
    }
}
