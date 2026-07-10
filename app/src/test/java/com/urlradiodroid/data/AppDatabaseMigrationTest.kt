package com.urlradiodroid.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// A standalone snapshot of the `radio_stations` table as it existed at schema version 2 (no
// unique indices) - see app/schemas/com.urlradiodroid.data.AppDatabase/2.json. Deliberately not
// reusing the real RadioStation/AppDatabase classes: they now carry the v3 unique indices, which
// would make this "legacy" database already have them, defeating the point of a migration test.
@Entity(tableName = "radio_stations")
internal data class LegacyRadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null,
)

@Dao
internal interface LegacyRadioStationDao {
    @Insert
    suspend fun insertStation(station: LegacyRadioStation): Long
}

@Database(entities = [LegacyRadioStation::class], version = 2, exportSchema = false)
internal abstract class LegacyV2Database : RoomDatabase() {
    abstract fun radioStationDao(): LegacyRadioStationDao
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    @Test
    fun `migration 2 to 3 drops pre-existing duplicates and enforces unique name and url`() =
        runTest {
            // Seed a v2-shaped database file with data, including a duplicate name that predates
            // the constraint (the app has always prevented this via its own checks, but the
            // migration must not crash - and must not lose data - if one slipped through anyway).
            val legacyDb =
                Room
                    .databaseBuilder(context, LegacyV2Database::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            legacyDb.radioStationDao().insertStation(
                LegacyRadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"),
            )
            legacyDb.radioStationDao().insertStation(
                LegacyRadioStation(name = "Jazz Radio", streamUrl = "http://example.com/jazz"),
            )
            legacyDb.radioStationDao().insertStation(
                LegacyRadioStation(name = "Rock FM", streamUrl = "http://example.com/rock-duplicate"),
            )
            legacyDb.close()

            // Reopen the same file through the real AppDatabase + migration, deliberately without
            // fallbackToDestructiveMigration, so Room strictly validates the post-migration schema
            // against what AppDatabase actually expects - if MIGRATION_2_3 is wrong, this throws.
            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(AppDatabase.MIGRATION_2_3)
                    .allowMainThreadQueries()
                    .build()
            val stations = migratedDb.radioStationDao().getAllStations()

            // The older "Rock FM" duplicate (lowest id) survives; the newer one was dropped.
            assertEquals(2, stations.size)
            assertEquals(setOf("Rock FM", "Jazz Radio"), stations.map { it.name }.toSet())
            assertTrue(stations.any { it.streamUrl == "http://example.com/rock" })

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    migratedDb.radioStationDao().insertStation(
                        RadioStation(name = "Jazz Radio", streamUrl = "http://example.com/new-jazz"),
                    )
                }
            }
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    migratedDb.radioStationDao().insertStation(
                        RadioStation(name = "New Station", streamUrl = "http://example.com/rock"),
                    )
                }
            }

            migratedDb.close()
        }
}
