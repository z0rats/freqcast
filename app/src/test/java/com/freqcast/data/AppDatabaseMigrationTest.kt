package com.freqcast.data

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
// unique indices) - see app/schemas/com.freqcast.data.AppDatabase/2.json. Deliberately not
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
// app/schemas/com.freqcast.data.AppDatabase/3.json. Same rationale as LegacyRadioStation:
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
// isFavorite, no genre column) - see app/schemas/com.freqcast.data.AppDatabase/4.json. Same
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

// A standalone snapshot of the `radio_stations` table as it existed at schema version 5 (adds
// genre, no isHls column) - see app/schemas/com.freqcast.data.AppDatabase/5.json. Same
// rationale as the other Legacy* entities: the real RadioStation entity already carries isHls,
// which would defeat this migration test.
@Entity(
    tableName = "radio_stations",
    indices = [
        androidx.room.Index(value = ["name"], unique = true),
        androidx.room.Index(value = ["streamUrl"], unique = true),
    ],
)
internal data class LegacyV5RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null,
    val isFavorite: Boolean = false,
    val genre: String? = null,
)

@Dao
internal interface LegacyV5RadioStationDao {
    @Insert
    suspend fun insertStation(station: LegacyV5RadioStation): Long
}

@Database(entities = [LegacyV5RadioStation::class], version = 5, exportSchema = false)
internal abstract class LegacyV5Database : RoomDatabase() {
    abstract fun radioStationDao(): LegacyV5RadioStationDao
}

// A standalone snapshot of the `radio_stations` table as it existed at schema version 6 (adds
// isHls, no radioBrowserUuid column) - see app/schemas/com.freqcast.data.AppDatabase/6.json.
// Same rationale as the other Legacy* entities: the real RadioStation entity already carries
// radioBrowserUuid, which would defeat this migration test.
@Entity(
    tableName = "radio_stations",
    indices = [
        androidx.room.Index(value = ["name"], unique = true),
        androidx.room.Index(value = ["streamUrl"], unique = true),
    ],
)
internal data class LegacyV6RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null,
    val isFavorite: Boolean = false,
    val genre: String? = null,
    val isHls: Boolean = false,
)

@Dao
internal interface LegacyV6RadioStationDao {
    @Insert
    suspend fun insertStation(station: LegacyV6RadioStation): Long
}

@Database(entities = [LegacyV6RadioStation::class], version = 6, exportSchema = false)
internal abstract class LegacyV6Database : RoomDatabase() {
    abstract fun radioStationDao(): LegacyV6RadioStationDao
}

// A standalone snapshot of the `radio_stations` table as it existed at schema version 7 (has
// isFavorite, no sortOrder column) - see app/schemas/com.freqcast.data.AppDatabase/7.json.
// Same rationale as the other Legacy* entities: the real RadioStation entity now carries
// sortOrder instead of isFavorite, which would defeat this migration test.
@Entity(
    tableName = "radio_stations",
    indices = [
        androidx.room.Index(value = ["name"], unique = true),
        androidx.room.Index(value = ["streamUrl"], unique = true),
    ],
)
internal data class LegacyV7RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null,
    val isFavorite: Boolean = false,
    val genre: String? = null,
    val isHls: Boolean = false,
    val radioBrowserUuid: String? = null,
)

@Dao
internal interface LegacyV7RadioStationDao {
    @Insert
    suspend fun insertStation(station: LegacyV7RadioStation): Long
}

@Database(entities = [LegacyV7RadioStation::class], version = 7, exportSchema = false)
internal abstract class LegacyV7Database : RoomDatabase() {
    abstract fun radioStationDao(): LegacyV7RadioStationDao
}

// A standalone snapshot of the `radio_stations` table as it existed at schema version 8 (has
// genre, no wake_alarms table yet) - see app/schemas/com.freqcast.data.AppDatabase/8.json. Same
// rationale as the other Legacy* entities: the real RadioStation entity now carries description
// instead of genre, which would defeat this migration test.
@Entity(
    tableName = "radio_stations",
    indices = [
        androidx.room.Index(value = ["name"], unique = true),
        androidx.room.Index(value = ["streamUrl"], unique = true),
    ],
)
internal data class LegacyV8RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null,
    val sortOrder: Int = 0,
    val genre: String? = null,
    val isHls: Boolean = false,
    val radioBrowserUuid: String? = null,
)

@Dao
internal interface LegacyV8RadioStationDao {
    @Insert
    suspend fun insertStation(station: LegacyV8RadioStation): Long
}

@Database(entities = [LegacyV8RadioStation::class], version = 8, exportSchema = false)
internal abstract class LegacyV8Database : RoomDatabase() {
    abstract fun radioStationDao(): LegacyV8RadioStationDao
}

// A standalone snapshot of the `radio_stations` table as it existed at schema version 9 (has
// genre, no description column) - see app/schemas/com.freqcast.data.AppDatabase/9.json. Same
// rationale as the other Legacy* entities: the real RadioStation entity now carries description
// instead of genre, which would defeat this migration test.
@Entity(
    tableName = "radio_stations",
    indices = [
        androidx.room.Index(value = ["name"], unique = true),
        androidx.room.Index(value = ["streamUrl"], unique = true),
    ],
)
internal data class LegacyV9RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null,
    val sortOrder: Int = 0,
    val genre: String? = null,
    val isHls: Boolean = false,
    val radioBrowserUuid: String? = null,
)

@Dao
internal interface LegacyV9RadioStationDao {
    @Insert
    suspend fun insertStation(station: LegacyV9RadioStation): Long
}

@Database(entities = [LegacyV9RadioStation::class, WakeAlarm::class], version = 9, exportSchema = false)
internal abstract class LegacyV9Database : RoomDatabase() {
    abstract fun radioStationDao(): LegacyV9RadioStationDao
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
            // All migrations must be registered since AppDatabase is now on version 8.
            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(
                        AppDatabase.MIGRATION_2_3,
                        AppDatabase.MIGRATION_3_4,
                        AppDatabase.MIGRATION_4_5,
                        AppDatabase.MIGRATION_5_6,
                        AppDatabase.MIGRATION_6_7,
                        AppDatabase.MIGRATION_7_8,
                        AppDatabase.MIGRATION_8_9,
                        AppDatabase.MIGRATION_9_10,
                    ).allowMainThreadQueries()
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
    fun `migration 3 to 4 preserves existing data`() =
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
                    .addMigrations(
                        AppDatabase.MIGRATION_3_4,
                        AppDatabase.MIGRATION_4_5,
                        AppDatabase.MIGRATION_5_6,
                        AppDatabase.MIGRATION_6_7,
                        AppDatabase.MIGRATION_7_8,
                        AppDatabase.MIGRATION_8_9,
                        AppDatabase.MIGRATION_9_10,
                    ).allowMainThreadQueries()
                    .build()
            val stations = migratedDb.radioStationDao().getAllStations()

            assertEquals(1, stations.size)
            assertEquals("Rock FM", stations[0].name)
            assertEquals("http://example.com/rock", stations[0].streamUrl)

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
                    .addMigrations(
                        AppDatabase.MIGRATION_4_5,
                        AppDatabase.MIGRATION_5_6,
                        AppDatabase.MIGRATION_6_7,
                        AppDatabase.MIGRATION_7_8,
                        AppDatabase.MIGRATION_8_9,
                        AppDatabase.MIGRATION_9_10,
                    ).allowMainThreadQueries()
                    .build()
            val stations = migratedDb.radioStationDao().getAllStations()

            assertEquals(1, stations.size)
            assertEquals("Rock FM", stations[0].name)
            assertEquals(null, stations[0].description)

            migratedDb.radioStationDao().updateStation(stations[0].copy(description = "Rock"))
            assertEquals("Rock", migratedDb.radioStationDao().getStationById(stations[0].id)?.description)

            migratedDb.close()
        }

    @Test
    fun `migration 5 to 6 adds isHls defaulting to false and preserves existing data`() =
        runTest {
            val legacyDb =
                Room
                    .databaseBuilder(context, LegacyV5Database::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            legacyDb.radioStationDao().insertStation(
                LegacyV5RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock", genre = "Rock"),
            )
            legacyDb.close()

            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(
                        AppDatabase.MIGRATION_5_6,
                        AppDatabase.MIGRATION_6_7,
                        AppDatabase.MIGRATION_7_8,
                        AppDatabase.MIGRATION_8_9,
                        AppDatabase.MIGRATION_9_10,
                    ).allowMainThreadQueries()
                    .build()
            val stations = migratedDb.radioStationDao().getAllStations()

            assertEquals(1, stations.size)
            assertEquals("Rock FM", stations[0].name)
            assertEquals("Rock", stations[0].description)
            assertFalse(stations[0].isHls)

            migratedDb.radioStationDao().updateStation(stations[0].copy(isHls = true))
            assertTrue(migratedDb.radioStationDao().getStationById(stations[0].id)?.isHls == true)

            migratedDb.close()
        }

    @Test
    fun `migration 6 to 7 adds a nullable radioBrowserUuid and preserves existing data`() =
        runTest {
            val legacyDb =
                Room
                    .databaseBuilder(context, LegacyV6Database::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            legacyDb.radioStationDao().insertStation(
                LegacyV6RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock", isHls = true),
            )
            legacyDb.close()

            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(
                        AppDatabase.MIGRATION_6_7,
                        AppDatabase.MIGRATION_7_8,
                        AppDatabase.MIGRATION_8_9,
                        AppDatabase.MIGRATION_9_10,
                    ).allowMainThreadQueries()
                    .build()
            val stations = migratedDb.radioStationDao().getAllStations()

            assertEquals(1, stations.size)
            assertEquals("Rock FM", stations[0].name)
            assertTrue(stations[0].isHls)
            assertEquals(null, stations[0].radioBrowserUuid)

            migratedDb.radioStationDao().updateStation(stations[0].copy(radioBrowserUuid = "uuid-1"))
            assertEquals(
                "uuid-1",
                migratedDb.radioStationDao().getStationById(stations[0].id)?.radioBrowserUuid,
            )

            migratedDb.close()
        }

    @Test
    fun `migration 7 to 8 replaces isFavorite with sortOrder matching the old favorites-first order`() =
        runTest {
            val legacyDb =
                Room
                    .databaseBuilder(context, LegacyV7Database::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            // Insertion order: Rock (not favorite), Jazz (favorite), Pop (not favorite). The old
            // effective order (isFavorite DESC, id ASC) would have been: Jazz, Rock, Pop.
            legacyDb.radioStationDao().insertStation(
                LegacyV7RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"),
            )
            legacyDb.radioStationDao().insertStation(
                LegacyV7RadioStation(name = "Jazz Radio", streamUrl = "http://example.com/jazz", isFavorite = true),
            )
            legacyDb.radioStationDao().insertStation(
                LegacyV7RadioStation(name = "Pop Hits", streamUrl = "http://example.com/pop"),
            )
            legacyDb.close()

            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10)
                    .allowMainThreadQueries()
                    .build()
            val stations = migratedDb.radioStationDao().getAllStations()

            // The old favorites-first order is frozen as the new sortOrder, so upgrading doesn't
            // visibly reshuffle anyone's list.
            assertEquals(listOf("Jazz Radio", "Rock FM", "Pop Hits"), stations.map { it.name })
            assertEquals(listOf(0, 1, 2), stations.map { it.sortOrder })

            // Manual reordering (drag-to-reorder) now works directly against sortOrder.
            migratedDb.radioStationDao().updateSortOrder(listOf(stations[2].id, stations[0].id, stations[1].id))
            val reordered = migratedDb.radioStationDao().getAllStations()
            assertEquals(listOf("Pop Hits", "Jazz Radio", "Rock FM"), reordered.map { it.name })

            migratedDb.close()
        }

    @Test
    fun `migration 8 to 9 adds an empty wake_alarms table and preserves existing station data`() =
        runTest {
            val legacyDb =
                Room
                    .databaseBuilder(context, LegacyV8Database::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            legacyDb.radioStationDao().insertStation(
                LegacyV8RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"),
            )
            legacyDb.close()

            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10)
                    .allowMainThreadQueries()
                    .build()

            val stations = migratedDb.radioStationDao().getAllStations()
            assertEquals(1, stations.size)
            assertEquals("Rock FM", stations[0].name)

            assertTrue(migratedDb.wakeAlarmDao().getAllAlarms().isEmpty())
            val id =
                migratedDb.wakeAlarmDao().insertAlarm(
                    WakeAlarm(
                        enabled = true,
                        hour = 7,
                        minute = 0,
                        stationName = "Rock FM",
                        streamUrl = "http://example.com/rock",
                    ),
                )
            assertEquals("Rock FM", migratedDb.wakeAlarmDao().getAlarmById(id)?.stationName)

            migratedDb.close()
        }

    @Test
    fun `migration 9 to 10 renames genre to description and preserves existing data`() =
        runTest {
            val legacyDb =
                Room
                    .databaseBuilder(context, LegacyV9Database::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            legacyDb.radioStationDao().insertStation(
                LegacyV9RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock", genre = "Rock"),
            )
            legacyDb.close()

            val migratedDb =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(AppDatabase.MIGRATION_9_10)
                    .allowMainThreadQueries()
                    .build()
            val stations = migratedDb.radioStationDao().getAllStations()

            assertEquals(1, stations.size)
            assertEquals("Rock FM", stations[0].name)
            assertEquals("Rock", stations[0].description)

            migratedDb.radioStationDao().updateStation(stations[0].copy(description = "Jazz"))
            assertEquals("Jazz", migratedDb.radioStationDao().getStationById(stations[0].id)?.description)

            migratedDb.close()
        }
}
