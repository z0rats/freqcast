package com.freqcast.ui

import androidx.room.Room
import com.freqcast.data.AppDatabase
import com.freqcast.data.CuratedStations
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.playback.SettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Direct coverage of the seam pulled out of `MainViewModel` — see `MainViewModelTest`'s
 * `seedCuratedStationsIfNeeded`/`clearSwipeHint` tests for the same behavior exercised end-to-end
 * through the ViewModel's delegating methods. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CuratedPackSeederTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var settingsStore: SettingsStore
    private lateinit var seeder: CuratedPackSeeder

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        repository = RadioStationRepository(database.radioStationDao())
        settingsStore = SettingsStore(RuntimeEnvironment.getApplication())
        seeder = CuratedPackSeeder(repository, settingsStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `seedIfNeeded inserts the curated pack once and sets swipeHintStationId`() =
        runTest {
            seeder.seedIfNeeded(RuntimeEnvironment.getApplication())

            val stations = database.radioStationDao().getAllStations()
            assertEquals(CuratedStations.pack.size, stations.size)
            assertEquals(CuratedStations.pack.map { it.name }, stations.map { it.name })
            assertTrue(stations.all { it.isCurated })
            assertEquals(stations[1].id, seeder.swipeHintStationId.value)
        }

    @Test
    fun `seedIfNeeded is a no-op on a later call`() =
        runTest {
            seeder.seedIfNeeded(RuntimeEnvironment.getApplication())
            val firstRunCount = database.radioStationDao().getAllStations().size

            seeder.seedIfNeeded(RuntimeEnvironment.getApplication())

            assertEquals(firstRunCount, database.radioStationDao().getAllStations().size)
        }

    @Test
    fun `seedIfNeeded skips a pack entry that already collides instead of throwing`() =
        runTest {
            // Simulates a broken hasSeededCuratedPack invariant (seeding somehow running again
            // against a non-empty library) - see insertStationIfAbsent's doc on RadioStationRepository.
            val firstEntry = CuratedStations.pack.first()
            database.radioStationDao().insertStation(firstEntry.copy(name = "Already Here"))

            seeder.seedIfNeeded(RuntimeEnvironment.getApplication())

            assertEquals(CuratedStations.pack.size, database.radioStationDao().getAllStations().size)
        }

    @Test
    fun `clearSwipeHint clears swipeHintStationId`() =
        runTest {
            seeder.seedIfNeeded(RuntimeEnvironment.getApplication())

            seeder.clearSwipeHint()

            assertNull(seeder.swipeHintStationId.value)
        }
}
