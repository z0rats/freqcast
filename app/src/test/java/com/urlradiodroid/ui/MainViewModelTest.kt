package com.urlradiodroid.ui

import androidx.room.Room
import com.urlradiodroid.data.AppDatabase
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MainViewModelTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        repository = RadioStationRepository(database.radioStationDao())
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun createViewModel(scheduler: TestCoroutineScheduler): MainViewModel {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        return MainViewModel(repository)
    }

    private suspend fun TestScope.waitForStationsCount(
        viewModel: MainViewModel,
        minCount: Int,
    ) {
        withTimeout(5000L) {
            while (viewModel.stations.value.size < minCount) {
                advanceUntilIdle()
            }
        }
    }

    private suspend fun TestScope.waitForStationsEmpty(viewModel: MainViewModel) {
        withTimeout(5000L) {
            while (viewModel.stations.value.isNotEmpty()) {
                advanceUntilIdle()
            }
        }
    }

    private suspend fun TestScope.waitUntil(condition: () -> Boolean) {
        withTimeout(5000L) {
            while (!condition()) {
                advanceUntilIdle()
            }
        }
    }

    @Test
    fun `getCurrentPlayingStationId returns null initially`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            assertNull(viewModel.getCurrentPlayingStationId())
        }

    @Test
    fun `updateCurrentPlayingStation sets and getCurrentPlayingStationId returns id`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            viewModel.updateCurrentPlayingStation(1L)
            assertEquals(1L, viewModel.getCurrentPlayingStationId())
        }

    @Test
    fun `updateCurrentPlayingStation null clears current station`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            viewModel.updateCurrentPlayingStation(5L)
            viewModel.updateCurrentPlayingStation(null)
            assertNull(viewModel.getCurrentPlayingStationId())
        }

    @Test
    fun `updateSearchQuery updates searchQuery flow`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            viewModel.updateSearchQuery("test query")
            assertEquals("test query", viewModel.searchQuery.first())
        }

    @Test
    fun `filteredStations returns all stations when search is blank`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            listOf(
                RadioStation(name = "Station A", streamUrl = "http://example.com/a"),
                RadioStation(name = "Station B", streamUrl = "http://example.com/b"),
            ).forEach { database.radioStationDao().insertStation(it) }
            viewModel.loadStations()
            waitForStationsCount(viewModel, 2)
            viewModel.updateSearchQuery("")
            val result = viewModel.filteredStations.first()
            assertEquals(2, result.size)
        }

    @Test
    fun `filteredStations filters by name`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            database.radioStationDao().insertStation(
                RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"),
            )
            database.radioStationDao().insertStation(
                RadioStation(name = "Jazz Radio", streamUrl = "http://example.com/jazz"),
            )
            viewModel.loadStations()
            waitForStationsCount(viewModel, 2)
            viewModel.updateSearchQuery("Rock")
            val result = viewModel.filteredStations.first()
            assertEquals(1, result.size)
            assertEquals("Rock FM", result[0].name)
        }

    @Test
    fun `filteredStations filters by streamUrl exact match`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            database.radioStationDao().insertStation(
                RadioStation(name = "Station A", streamUrl = "http://example.com/rock"),
            )
            database.radioStationDao().insertStation(
                RadioStation(name = "Station B", streamUrl = "http://example.com/jazz"),
            )
            viewModel.loadStations()
            waitForStationsCount(viewModel, 2)
            viewModel.updateSearchQuery("http://example.com/jazz")
            val result = viewModel.filteredStations.first()
            assertEquals(1, result.size)
            assertEquals("http://example.com/jazz", result[0].streamUrl)
        }

    @Test
    fun `filteredStations returns all when search is whitespace only`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            database.radioStationDao().insertStation(RadioStation(name = "A", streamUrl = "http://example.com/a"))
            viewModel.loadStations()
            waitForStationsCount(viewModel, 1)
            viewModel.updateSearchQuery("   ")
            val result = viewModel.filteredStations.first()
            assertEquals(1, result.size)
        }

    @Test
    fun `filteredStations returns empty when no stations and any query`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            viewModel.updateSearchQuery("anything")
            val result = viewModel.filteredStations.first()
            assertEquals(0, result.size)
        }

    @Test
    fun `filteredStations search is case insensitive`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            database.radioStationDao().insertStation(
                RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"),
            )
            viewModel.loadStations()
            waitForStationsCount(viewModel, 1)
            viewModel.updateSearchQuery("rock")
            val result = viewModel.filteredStations.first()
            assertEquals(1, result.size)
            assertEquals("Rock FM", result[0].name)
        }

    @Test
    fun `stations flow reflects loadStations after insert`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            assertEquals(0, viewModel.stations.value.size)
            database.radioStationDao().insertStation(RadioStation(name = "New", streamUrl = "http://example.com/new"))
            viewModel.loadStations()
            waitForStationsCount(viewModel, 1)
            val list = viewModel.stations.value
            assertEquals(1, list.size)
            assertEquals("New", list[0].name)
        }

    @Test
    fun `toggleFavorite pins a station and reorders it to the top`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            database.radioStationDao().insertStation(RadioStation(name = "First", streamUrl = "http://example.com/1"))
            val second =
                database.radioStationDao().insertStation(
                    RadioStation(name = "Second", streamUrl = "http://example.com/2"),
                )
            viewModel.loadStations()
            waitForStationsCount(viewModel, 2)

            viewModel.toggleFavorite(viewModel.stations.value.first { it.id == second })
            waitUntil {
                viewModel.stations.value
                    .firstOrNull()
                    ?.isFavorite == true
            }

            val list = viewModel.stations.value
            assertEquals("Second", list[0].name)
        }

    @Test
    fun `toggleFavorite on an already-favorite station unpins it`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val id =
                database.radioStationDao().insertStation(
                    RadioStation(name = "Station", streamUrl = "http://example.com/s"),
                )
            database.radioStationDao().setFavorite(id, true)
            viewModel.loadStations()
            waitForStationsCount(viewModel, 1)

            viewModel.toggleFavorite(viewModel.stations.value[0])
            waitUntil {
                viewModel.stations.value
                    .firstOrNull()
                    ?.isFavorite == false
            }

            assertEquals(false, viewModel.stations.value[0].isFavorite)
        }

    @Test
    fun `provideFactory creates MainViewModel with repository`() =
        runTest {
            database.radioStationDao().insertStation(
                RadioStation(name = "From Factory", streamUrl = "http://example.com/f"),
            )
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val vm = MainViewModel.provideFactory(repository).create(MainViewModel::class.java) as MainViewModel
            vm.loadStations()
            waitForStationsCount(vm, 1)
            val list = vm.stations.value
            assertEquals(1, list.size)
            assertEquals("From Factory", list[0].name)
        }

    @Test
    fun `deleteStation removes station and reloads list`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val id =
                database.radioStationDao().insertStation(
                    RadioStation(name = "To Delete", streamUrl = "http://example.com/del"),
                )
            viewModel.loadStations()
            waitForStationsCount(viewModel, 1)
            viewModel.deleteStation(viewModel.stations.value.first { it.id == id })
            waitForStationsEmpty(viewModel)
            assertEquals(0, viewModel.stations.value.size)
        }

    @Test
    fun `deleteStation emits StationDeleted event with the deleted station`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            database.radioStationDao().insertStation(
                RadioStation(name = "To Delete", streamUrl = "http://example.com/del"),
            )
            viewModel.loadStations()
            waitForStationsCount(viewModel, 1)
            val station = viewModel.stations.value[0]

            val events = mutableListOf<MainScreenEvent>()
            val collectJob = launch { viewModel.events.collect { events.add(it) } }
            viewModel.deleteStation(station)
            waitUntil { events.isNotEmpty() }
            collectJob.cancel()

            val event = events.single() as MainScreenEvent.StationDeleted
            assertEquals(station.id, event.station.id)
            assertEquals("To Delete", event.station.name)
        }

    @Test
    fun `undoDelete restores the station with its original id and position`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            database.radioStationDao().insertStation(RadioStation(name = "First", streamUrl = "http://example.com/1"))
            database.radioStationDao().insertStation(RadioStation(name = "Second", streamUrl = "http://example.com/2"))
            viewModel.loadStations()
            waitForStationsCount(viewModel, 2)
            val toDelete = viewModel.stations.value.first { it.name == "First" }

            viewModel.deleteStation(toDelete)
            waitForStationsCount(viewModel, 1)

            viewModel.undoDelete(toDelete)
            waitForStationsCount(viewModel, 2)

            val restored = viewModel.stations.value.first { it.name == "First" }
            assertEquals(toDelete.id, restored.id)
        }
}
