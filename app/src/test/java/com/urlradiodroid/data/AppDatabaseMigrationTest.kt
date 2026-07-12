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
import org.junit.Assert.assertFalse
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

// A standalone snapshot of the `radio_stations` table as it existed at schema version 3 (unique
// indices on name/streamUrl, no isFavorite column) - see
// app/schemas/com.urlradiodroid.data.AppDatabase/3.json. Same rationale as LegacyRadioStation:
// the real RadioStation entity already carries isFavorite, which would defeat this migration test.
@Entity(
    tableName = "radio_stations",
    indices = [
        androidx.room.Index(value = ["name"], unique = true),
        androidx.room.Index(value = ["streamUrl"], unique = true),
    ],
)
internal data class LegacyV3RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null,
)

@Dao
internal interface LegacyV3RadioStationDao {
    @Insert
    suspend fun insertStation(station: LegacyV3RadioStation): Long
}

@Database(entities = [LegacyV3RadioStation::class], version = 3, exportSchema = false)
internal abstract class LegacyV3Database : RoomDatabase() {
    abstract fun radioStationDao(): LegacyV3RadioStationDao
}

// A standalone snapshot of the `radio_stations` table as it existed at schema version 4 (adds
// isFavorite, no genre column) - see app/schemas/com.urlradiodroid.data.AppDatabase/4.json. Same
// rationale as the other Legacy* entities: the real RadioStation entity already carries genre,
// which would defeat this migration test.
@Entity(
    tableName = "radio_stations",
    indices = [
        androidx.room.Index(value = ["name"], unique = true),
        androidx.room.Index(value = ["streamUrl"], unique = true),
    ],
)
internal data class LegacyV4RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null,
    val isFavorite: Boolean = false,
)

@Dao
internal interface LegacyV4RadioStationDao {
    @Insert
    suspend fun insertStation(station: LegacyV4RadioStation): Long
}

@Database(entities = [LegacyV4RadioStation::class], version = 4, exportSchema = false)
internal abstract class LegacyV4Database : RoomDatabase() {
    abstract fun radioStationDao(): LegacyV4RadioStationDao
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

            // Reopen the same file through the real AppDatabase + migrations, deliberately without
            // fallbackToDestructiveMigration, so Room strictly validates the post-migration schema
            // against what AppDatabase actually expects - if a migration is wrong, this throws.
            // All three migrations must be registered since AppDatabase is now on version 5.
            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
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

    @Test
    fun `migration 3 to 4 adds isFavorite defaulting to false and preserves existing data`() =
        runTest {
            val legacyDb =
                Room
                    .databaseBuilder(context, LegacyV3Database::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            legacyDb.radioStationDao().insertStation(
                LegacyV3RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"),
            )
            legacyDb.close()

            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
                    .allowMainThreadQueries()
                    .build()
            val stations = migratedDb.radioStationDao().getAllStations()

            assertEquals(1, stations.size)
            assertEquals("Rock FM", stations[0].name)
            assertEquals("http://example.com/rock", stations[0].streamUrl)
            assertFalse(stations[0].isFavorite)

            migratedDb.radioStationDao().setFavorite(stations[0].id, true)
            assertTrue(migratedDb.radioStationDao().getStationById(stations[0].id)?.isFavorite == true)

            migratedDb.close()
        }

    @Test
    fun `migration 4 to 5 adds a nullable genre and preserves existing data`() =
        runTest {
            val legacyDb =
                Room
                    .databaseBuilder(context, LegacyV4Database::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            legacyDb.radioStationDao().insertStation(
                LegacyV4RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock", isFavorite = true),
            )
            legacyDb.close()

            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(AppDatabase.MIGRATION_4_5)
                    .allowMainThreadQueries()
                    .build()
            val stations = migratedDb.radioStationDao().getAllStations()

            assertEquals(1, stations.size)
            assertEquals("Rock FM", stations[0].name)
            assertTrue(stations[0].isFavorite)
            assertEquals(null, stations[0].genre)

            migratedDb.radioStationDao().updateStation(stations[0].copy(genre = "Rock"))
            assertEquals("Rock", migratedDb.radioStationDao().getStationById(stations[0].id)?.genre)

            migratedDb.close()
        }
}
