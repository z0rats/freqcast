package com.urlradiodroid.ui

import androidx.room.Room
import com.urlradiodroid.data.AppDatabase
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
            viewModel.deleteStation(id)
            waitForStationsEmpty(viewModel)
            assertEquals(0, viewModel.stations.value.size)
        }
}
