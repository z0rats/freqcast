package com.freqcast.ui

import android.Manifest
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import androidx.room.Room
import com.freqcast.R
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioBrowserApi
import com.freqcast.data.RadioBrowserStation
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.util.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [RadioBrowserApi]/[com.freqcast.util.StreamValidator] hop onto the real `Dispatchers.IO`
 * for their MockWebServer round-trip, off the virtual test scheduler — so, like
 * [MainViewModelTest], assertions after a network-touching call use [awaitTrue] (a real-time poll)
 * rather than a single [advanceUntilIdle], which only fast-forwards the test dispatcher's queue.
 * Native graphics (not the legacy Robolectric shadow) so the favicon-download tests' real PNG
 * bytes decode back through [com.freqcast.util.IconStorage.saveImageBytes], same rationale
 * as `IconStorageTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                // Room otherwise hops suspend DAO calls onto its own real thread pool, which races
                // against the virtual test dispatcher (e.g. an init{}-launched load can still be
                // in flight when tearDown() resets Dispatchers.Main) — running it on the calling
                // thread keeps every DB call inside the same virtual-time-controlled dispatcher.
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
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

    private fun pngBytesFor(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private fun createViewModel(
        scheduler: TestCoroutineScheduler,
        locationProvider: LocationProvider = LocationProvider(RuntimeEnvironment.getApplication()),
    ): DiscoverStationsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        return DiscoverStationsViewModel(
            repository,
            RuntimeEnvironment.getApplication(),
            RadioBrowserApi(baseUrl = server.url("/")),
            locationProvider,
        )
    }

    /**
     * Grants ACCESS_COARSE_LOCATION and, if [fix] is non-null, seeds it as the network provider's
     * last-known location so [LocationProvider.getCurrentLocation] resolves it instantly instead
     * of waiting on a real `requestSingleUpdate` round-trip (see [LocationProviderTest] for that
     * path's own coverage) — same "real, test-substitutable collaborator" shape as [server].
     */
    private fun nearbyLocationProvider(
        fix: Location? =
            Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = 52.5
                longitude = 13.4
            },
    ): LocationProvider {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val locationManager = context.getSystemService(LocationManager::class.java)
        if (fix != null) {
            shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            shadowOf(locationManager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix)
        }
        return LocationProvider(context, locationManager)
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
    fun `addStation persists the directory's tags as the station's description`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val station = RadioBrowserStation("u1", "New FM", "http://example.com/new", "US", "rock,pop", 0)

            viewModel.addStation(station)
            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains(station.url)
            }

            assertEquals("rock,pop", database.radioStationDao().getAllStations()[0].description)
        }

    @Test
    fun `addStation persists the directory's hls flag`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val station = RadioBrowserStation("u1", "New FM", "http://example.com/new", "", "", 0, hls = true)

            viewModel.addStation(station)
            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains(station.url)
            }

            assertTrue(database.radioStationDao().getAllStations()[0].isHls)
        }

    @Test
    fun `addStation persists the directory's uuid as radioBrowserUuid`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val station = RadioBrowserStation("u1", "New FM", "http://example.com/new", "", "", 0)

            viewModel.addStation(station)
            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains(station.url)
            }

            assertEquals("u1", database.radioStationDao().getAllStations()[0].radioBrowserUuid)
        }

    @Test
    fun `addStation downloads the favicon in the background and sets it as customIcon`() =
        runTest {
            server.enqueue(MockResponse().setBody(Buffer().write(pngBytesFor(64, 64))))
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val faviconUrl = server.url("/favicon.png").toString()
            val station =
                RadioBrowserStation("u1", "New FM", "http://example.com/new", "", "", 0, favicon = faviconUrl)

            viewModel.addStation(station)
            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains(station.url)
            }

            // The favicon download+DB update chains multiple real-dispatcher hops (network IO,
            // bitmap decode, two Room calls), each needing actual wall-clock time to complete —
            // more than awaitTrue's virtual-time-only advanceUntilIdle() busy-loop reliably gives
            // them. Poll with a real Thread.sleep between attempts instead, reading the DB via a
            // plain runBlocking (a genuinely separate blocking call, not tied to the test's virtual
            // scheduler, so it can't deadlock the way calling a suspend fun from inside awaitTrue's
            // poll lambda would — see that helper's doc).
            var customIcon: String? = null
            val deadline = System.currentTimeMillis() + 5000
            while (customIcon == null && System.currentTimeMillis() < deadline) {
                advanceUntilIdle()
                customIcon =
                    runBlocking {
                        database
                            .radioStationDao()
                            .getAllStations()
                            .firstOrNull()
                            ?.customIcon
                    }
                if (customIcon == null) Thread.sleep(20)
            }

            assertTrue(customIcon != null && File(customIcon).exists())
        }

    @Test
    fun `addStation leaves customIcon null when the station has no favicon`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val station = RadioBrowserStation("u1", "New FM", "http://example.com/new", "", "", 0)

            viewModel.addStation(station)
            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains(station.url)
            }

            assertNull(database.radioStationDao().getAllStations()[0].customIcon)
        }

    @Test
    fun `addStation attempts the favicon download but leaves customIcon null on failure`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            val faviconUrl = server.url("/missing.png").toString()
            val station =
                RadioBrowserStation("u1", "New FM", "http://example.com/new", "", "", 0, favicon = faviconUrl)

            viewModel.addStation(station)
            awaitTrue {
                viewModel.uiState.value.addedUrls
                    .contains(station.url)
            }
            // Confirms the background download actually ran (not just that nothing changed before
            // it started) — a 404 response can never reach the customIcon-setting code path, so
            // there's nothing further worth polling for once the server has seen the request.
            val request = server.takeRequest(5, TimeUnit.SECONDS)

            assertEquals("/missing.png", request?.path)
            assertNull(database.radioStationDao().getAllStations()[0].customIcon)
        }

    @Test
    fun `onModeChange to LANGUAGE searches by language`() =
        runTest {
            server.enqueue(MockResponse().setBody("[]"))
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()

            viewModel.onModeChange(DiscoverSearchMode.LANGUAGE)
            viewModel.onQueryChange("english")
            awaitTrue { viewModel.uiState.value.hasSearched }

            val request = server.takeRequest()
            assertTrue(request.path?.contains("language=english") == true)
        }

    @Test
    fun `onModeChange to NEARBY does not trigger a search by itself`() =
        runTest {
            val viewModel = createViewModel(testScheduler, nearbyLocationProvider())
            advanceUntilIdle()

            viewModel.onModeChange(DiscoverSearchMode.NEARBY)
            advanceUntilIdle()

            assertEquals(0, server.requestCount)
            assertEquals(DiscoverSearchMode.NEARBY, viewModel.uiState.value.mode)
            assertFalse(viewModel.uiState.value.hasSearched)
        }

    @Test
    fun `searchNearby sends the resolved location and populates results`() =
        runTest {
            server.enqueue(
                MockResponse().setBody("""[{"name":"Local FM","url":"http://example.com/local","distance":1200.0}]"""),
            )
            val viewModel = createViewModel(testScheduler, nearbyLocationProvider())
            advanceUntilIdle()

            viewModel.searchNearby()
            awaitTrue { viewModel.uiState.value.hasSearched }

            val request = server.takeRequest()
            assertTrue(request.path?.contains("geo_lat=52.5") == true)
            assertTrue(request.path?.contains("geo_long=13.4") == true)
            assertEquals(1, viewModel.uiState.value.results.size)
            assertEquals(
                1.2,
                viewModel.uiState.value.results[0]
                    .distanceKm,
            )
        }

    @Test
    fun `searchNearby sets errorRes when no location can be resolved`() =
        runTest {
            val viewModel = createViewModel(testScheduler, nearbyLocationProvider(fix = null))
            advanceUntilIdle()

            viewModel.searchNearby()
            awaitTrue { viewModel.uiState.value.hasSearched }

            assertEquals(0, server.requestCount)
            assertEquals(R.string.discover_location_unavailable, viewModel.uiState.value.errorRes)
        }

    @Test
    fun `onLocationPermissionDenied sets locationPermissionDenied`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()

            viewModel.onLocationPermissionDenied()

            assertTrue(viewModel.uiState.value.locationPermissionDenied)
        }

    @Test
    fun `onModeChange away from NEARBY clears locationPermissionDenied`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle()
            viewModel.onModeChange(DiscoverSearchMode.NEARBY)
            viewModel.onLocationPermissionDenied()

            viewModel.onModeChange(DiscoverSearchMode.NAME)

            assertFalse(viewModel.uiState.value.locationPermissionDenied)
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
