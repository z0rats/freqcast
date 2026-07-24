package com.freqcast.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RadioStation::class, WakeAlarm::class], version = 10, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun radioStationDao(): RadioStationDao

    abstract fun wakeAlarmDao(): WakeAlarmDao

    companion object {
        /**
         * Adds unique constraints on `name`/`streamUrl`. Duplicates could only exist from before
         * these constraints were enforced at the app level, but if any slipped through, keep the
         * oldest row (lowest id) for each and drop the rest so the new unique indices can be created.
         */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "DELETE FROM radio_stations WHERE id NOT IN " +
                            "(SELECT MIN(id) FROM radio_stations GROUP BY name)",
                    )
                    db.execSQL(
                        "DELETE FROM radio_stations WHERE id NOT IN " +
                            "(SELECT MIN(id) FROM radio_stations GROUP BY streamUrl)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_radio_stations_name` " +
                            "ON `radio_stations` (`name`)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_radio_stations_streamUrl` " +
                            "ON `radio_stations` (`streamUrl`)",
                    )
                }
            }

        /** Adds the favorites/pinning flag; existing rows default to not-favorite. */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE radio_stations ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        /** Adds the optional genre/tag; existing rows default to none. */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE radio_stations ADD COLUMN genre TEXT DEFAULT NULL",
                    )
                }
            }

        /**
         * Adds the known-HLS hint from the Radio Browser directory's `hls` flag; existing rows
         * default to false and fall back to isHlsUrl()'s URL heuristic, same as manual adds.
         */
        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE radio_stations ADD COLUMN isHls INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        /**
         * Adds the Radio Browser directory's stationuuid, used to register plays as "clicks";
         * existing rows default to null (manual adds, and pre-migration Discover-added stations
         * that predate this column) and simply don't register clicks.
         */
        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE radio_stations ADD COLUMN radioBrowserUuid TEXT DEFAULT NULL",
                    )
                }
            }

        /**
         * Replaces the favorites/pinning flag with a manual drag-to-reorder position: `sortOrder`
         * (ascending). Unlike the ADD-COLUMN-only migrations above, dropping `isFavorite` needs a
         * full table rebuild — SQLite's `ALTER TABLE ... DROP COLUMN` support depends on the
         * bundled SQLite version, which isn't guaranteed across every API level down to minSdk 29,
         * so this follows the standard "create new table, copy data, drop old, rename" pattern
         * instead. Existing rows get `sortOrder` backfilled from their *current* effective order
         * (`isFavorite DESC, id ASC`, the exact `ORDER BY` `getAllStations()` used to use) via a
         * correlated-subquery rank, so upgrading doesn't visibly reshuffle anyone's list — it just
         * freezes the old favorites-first order as the new starting manual order.
         */
        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE radio_stations_new (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, `streamUrl` TEXT NOT NULL, `customIcon` TEXT, " +
                            "`sortOrder` INTEGER NOT NULL DEFAULT 0, `genre` TEXT, " +
                            "`isHls` INTEGER NOT NULL, `radioBrowserUuid` TEXT)",
                    )
                    db.execSQL(
                        "INSERT INTO radio_stations_new " +
                            "(id, name, streamUrl, customIcon, sortOrder, genre, isHls, radioBrowserUuid) " +
                            "SELECT id, name, streamUrl, customIcon, " +
                            "(SELECT COUNT(*) FROM radio_stations s2 " +
                            "WHERE (s2.isFavorite > radio_stations.isFavorite) " +
                            "OR (s2.isFavorite = radio_stations.isFavorite AND s2.id <= radio_stations.id)) - 1, " +
                            "genre, isHls, radioBrowserUuid " +
                            "FROM radio_stations",
                    )
                    db.execSQL("DROP TABLE radio_stations")
                    db.execSQL("ALTER TABLE radio_stations_new RENAME TO radio_stations")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_radio_stations_name` " +
                            "ON `radio_stations` (`name`)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_radio_stations_streamUrl` " +
                            "ON `radio_stations` (`streamUrl`)",
                    )
                }
            }

        /**
         * Adds the `wake_alarms` table backing multiple wake-up alarms (see
         * [com.freqcast.ui.AlarmListScreen]/[com.freqcast.ui.AlarmEditScreen]), replacing
         * the single SharedPreferences-backed alarm. Existing users' one alarm isn't migrated here —
         * that needs [com.freqcast.ui.playback.AlarmStateStore], which a schema migration has no
         * access to — [AlarmRepository.migrateLegacyAlarmIfNeeded] does that import separately, on
         * first alarm-list load / boot / alarm firing after upgrading.
         */
        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS wake_alarms (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`enabled` INTEGER NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, " +
                            "`stationName` TEXT, `streamUrl` TEXT)",
                    )
                }
            }

        /**
         * Renames `genre` to `description` - same free-text column, now framed as a general note
         * rather than a music genre. Uses the same create/copy/drop/rename rebuild as
         * [MIGRATION_7_8] rather than `RENAME COLUMN`, for the same cross-API-level SQLite
         * version safety reason documented there.
         */
        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE radio_stations_new (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, `streamUrl` TEXT NOT NULL, `customIcon` TEXT, " +
                            "`sortOrder` INTEGER NOT NULL DEFAULT 0, `description` TEXT, " +
                            "`isHls` INTEGER NOT NULL, `radioBrowserUuid` TEXT)",
                    )
                    db.execSQL(
                        "INSERT INTO radio_stations_new " +
                            "(id, name, streamUrl, customIcon, sortOrder, description, isHls, radioBrowserUuid) " +
                            "SELECT id, name, streamUrl, customIcon, sortOrder, genre, isHls, radioBrowserUuid " +
                            "FROM radio_stations",
                    )
                    db.execSQL("DROP TABLE radio_stations")
                    db.execSQL("ALTER TABLE radio_stations_new RENAME TO radio_stations")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_radio_stations_name` " +
                            "ON `radio_stations` (`name`)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_radio_stations_streamUrl` " +
                            "ON `radio_stations` (`streamUrl`)",
                    )
                }
            }

        @Volatile
        private var instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                val newInstance =
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "radio_database",
                        ).addMigrations(
                            MIGRATION_2_3,
                            MIGRATION_3_4,
                            MIGRATION_4_5,
                            MIGRATION_5_6,
                            MIGRATION_6_7,
                            MIGRATION_7_8,
                            MIGRATION_8_9,
                            MIGRATION_9_10,
                        )
                        // Safety net only for schema jumps with no explicit migration
                        // (e.g. pre-1.0 installs skipping straight to a future version).
                        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                        .build()
                instance = newInstance
                newInstance
            }
    }
}
