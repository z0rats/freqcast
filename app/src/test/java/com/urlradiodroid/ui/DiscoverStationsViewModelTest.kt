package com.urlradiodroid.ui

import androidx.room.Room
import com.urlradiodroid.R
import com.urlradiodroid.data.AppDatabase
import com.urlradiodroid.data.RadioBrowserApi
import com.urlradiodroid.data.RadioBrowserStation
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [RadioBrowserApi]/[com.urlradiodroid.util.StreamValidator] hop onto the real `Dispatchers.IO`
 * for their MockWebServer round-trip, off the virtual test scheduler — so, like
 * [MainViewModelTest], assertions after a network-touching call use [awaitTrue] (a real-time poll)
 * rather than a single [advanceUntilIdle], which only fast-forwards the test dispatcher's queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiscoverStationsViewModelTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RadioStationRepository(database.radioStationDao())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun createViewModel(scheduler: TestCoroutineScheduler): DiscoverStationsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        return DiscoverStationsViewModel(repository, RadioBrowserApi(baseUrl = server.url("/")))
    }

    private suspend fun TestScope.awaitTrue(
        timeoutMs: Long = 5000L,
        poll: () -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            while (!poll()) {
                advanceUntilIdle()
            }
        }
    }

    @Test
    fun `search is debounced and only fires once after typing settles`() =
        runTest {
            server.enqueue(MockResponse().setBody("""[{"name":"Jazz FM","url":"http://example.com/jazz"}]"""))
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()

            viewModel.onQueryChange("j")
            advanceTimeBy(100)
            viewModel.onQueryChange("ja")
            advanceTimeBy(100)
            viewModel.onQueryChange("jazz")
            awaitTrue { viewModel.uiState.value.hasSearched }

            assertEquals(1, server.requestCount)
            assertEquals(1, viewModel.uiState.value.results.size)
            assertEquals(
                "Jazz FM",
                viewModel.uiState.value.results[0]
                    .name,
            )
        }

    @Test
    fun `blank query clears results without calling the API`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()

            viewModel.onQueryChange("   ")
            advanceUntilIdle()

            assertEquals(0, server.requestCount)
            assertTrue(
                viewModel.uiState.value.results
                    .isEmpty(),
            )
            assertFalse(viewModel.uiState.value.hasSearched)
        }

    @Test
    fun `search failure sets errorRes`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()

            viewModel.onQueryChange("jazz")
            awaitTrue { viewModel.uiState.value.hasSearched }

            assertEquals(R.string.discover_search_error, viewModel.uiState.value.errorRes)
        }

    @Test
    fun `addStation inserts the station and marks its url as added`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val station = RadioBrowserStation("u1", "New FM", "http://example.com/new", "", "", 0)

            viewModel.addStation(station)
            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains(station.url)
            }

            assertEquals("New FM", database.radioStationDao().getAllStations()[0].name)
        }

    @Test
    fun `addStation with a url already in the library marks it added without inserting again`() =
        runTest {
            database.radioStationDao().insertStation(
                RadioStation(name = "Existing", streamUrl = "http://example.com/dup"),
            )
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val station = RadioBrowserStation("u1", "Duplicate", "http://example.com/dup", "", "", 0)

            viewModel.addStation(station)
            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains(station.url)
            }

            assertEquals(1, database.radioStationDao().getAllStations().size)
        }

    @Test
    fun `addStation disambiguates a name collision with a different url`() =
        runTest {
            database.radioStationDao().insertStation(
                RadioStation(name = "Radio X", streamUrl = "http://example.com/old"),
            )
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val station = RadioBrowserStation("u1", "Radio X", "http://example.com/new", "", "", 0)

            viewModel.addStation(station)
            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains(station.url)
            }

            val names = database.radioStationDao().getAllStations().map { it.name }
            assertTrue(names.contains("Radio X (2)"))
        }

    @Test
    fun `existing library urls are marked as added on init`() =
        runTest {
            database.radioStationDao().insertStation(
                RadioStation(name = "Existing", streamUrl = "http://example.com/already"),
            )
            val viewModel = createViewModel(testScheduler)

            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains("http://example.com/already")
            }
        }
}
