package com.urlradiodroid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RadioStation::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun radioStationDao(): RadioStationDao

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
                        ).addMigrations(MIGRATION_2_3)
                        // Safety net only for schema jumps with no explicit migration
                        // (e.g. pre-1.0 installs skipping straight to a future version).
                        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                        .build()
                instance = newInstance
                newInstance
            }
    }
}
