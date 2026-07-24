package com.freqcast.ui

import android.graphics.Bitmap
import androidx.room.Room
import com.freqcast.R
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.StationUrlResolver
import com.freqcast.util.StreamValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [StreamValidator] hops onto the real `Dispatchers.IO` for its MockWebServer round-trip, off the
 * virtual test scheduler — so, like [MainViewModelTest], assertions after [AddStationViewModel.save]
 * use [awaitTrue] (a real-time poll) rather than a single [advanceUntilIdle].
 * Native graphics (not the legacy Robolectric shadow) so the favicon-download tests' real PNG
 * bytes decode back through [com.freqcast.util.IconStorage.saveImageBytes], same rationale as
 * `DiscoverStationsViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AddStationViewModelTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
                .allowMainThreadQueries()
                // See DiscoverStationsViewModelTest: keeps Room's suspend DAO calls off its own
                // real thread pool so they can't race the virtual test dispatcher.
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        repository = RadioStationRepository(database.radioStationDao())
        server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        scheduler: TestCoroutineScheduler,
        editingStationId: Long? = null,
        stationUrlResolver: StationUrlResolver? = null,
    ): AddStationViewModel {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val streamValidator = StreamValidator(client = OkHttpClient())
        return AddStationViewModel(
            repository,
            editingStationId,
            RuntimeEnvironment.getApplication(),
            streamValidator,
            stationUrlResolver ?: StationUrlResolver(streamValidator = streamValidator),
        )
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
    fun `selecting an emoji sets it as the custom icon`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            viewModel.onEmojiIconSelected("🎸")

            assertEquals("🎸", viewModel.uiState.value.customIcon)
        }

    @Test
    fun `selecting an image path sets it as the custom icon`() =
        runTest {
            val viewModel = createViewModel(testScheduler)

            viewModel.onImageIconSelected("/data/user/0/com.freqcast/files/station_icons/a.jpg")

            assertEquals("/data/user/0/com.freqcast/files/station_icons/a.jpg", viewModel.uiState.value.customIcon)
        }

    @Test
    fun `removing the icon clears it`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.onEmojiIconSelected("🎸")

            viewModel.onRemoveIcon()

            assertNull(viewModel.uiState.value.customIcon)
        }

    @Test
    fun `editing a station loads its existing custom icon`() =
        runTest {
            val id =
                repository.insertStation(
                    RadioStation(name = "Jazz FM", streamUrl = "http://example.com", customIcon = "🎷"),
                )
            val viewModel = createViewModel(testScheduler, editingStationId = id)

            awaitTrue { viewModel.uiState.value.customIcon == "🎷" }
        }

    @Test
    fun `editing a station loads its existing description`() =
        runTest {
            val id =
                repository.insertStation(
                    RadioStation(name = "Jazz FM", streamUrl = "http://example.com", description = "jazz,smooth"),
                )
            val viewModel = createViewModel(testScheduler, editingStationId = id)

            awaitTrue { viewModel.uiState.value.description == "jazz,smooth" }
        }

    @Test
    fun `saving a new station persists the entered description`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.onNameChange("New FM")
            viewModel.onUrlChange(server.url("/stream").toString())
            viewModel.onDescriptionChange("rock")

            viewModel.save()

            // isSaving starts false, so polling !isSaving alone would race ahead of the save
            // (see the class doc); waiting for the true->false transition avoids that. Can't poll
            // the DB row directly either: a suspend call inside the poll condition would itself
            // suspend on Room's real executor thread, and since nothing else drives the virtual
            // scheduler forward while we're suspended, save()'s own coroutine never progresses -
            // a deadlock only `withTimeout` breaks out of.
            awaitTrue { viewModel.uiState.value.isSaving }
            awaitTrue { !viewModel.uiState.value.isSaving }
            assertEquals("rock", database.radioStationDao().getAllStations()[0].description)
        }

    @Test
    fun `pasting a station homepage resolves and saves the stream url found on the page`() =
        runTest {
            // A dedicated server, rather than the class's shared `server` (which setup() pre-seeds
            // with one always-200 response for the simple direct-stream-url tests), so this test
            // controls its exact request/response sequence: HEAD on the homepage (content-type
            // check), GET on the homepage (the actual scrape), then HEAD on the stream it finds.
            val homepageServer = MockWebServer()
            homepageServer.start()
            try {
                homepageServer.enqueue(MockResponse().setHeader("Content-Type", "text/html"))
                homepageServer.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(
                            """<html><body><audio src="${homepageServer.url("/stream.mp3")}"></audio></body></html>""",
                        ),
                )
                homepageServer.enqueue(MockResponse().setResponseCode(200))

                val viewModel = createViewModel(testScheduler)
                viewModel.onNameChange("New FM")
                viewModel.onUrlChange(homepageServer.url("/").toString())

                viewModel.save()

                awaitTrue { viewModel.uiState.value.isSaving }
                awaitTrue { !viewModel.uiState.value.isSaving }
                assertEquals(homepageServer.url("/stream.mp3").toString(), viewModel.uiState.value.url)
                assertEquals(
                    homepageServer.url("/stream.mp3").toString(),
                    database.radioStationDao().getAllStations()[0].streamUrl,
                )
            } finally {
                homepageServer.shutdown()
            }
        }

    @Test
    fun `saving a direct stream url shows the checking-url stage while probing`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.onNameChange("New FM")
            viewModel.onUrlChange(server.url("/stream").toString())

            viewModel.save()

            awaitTrue { viewModel.uiState.value.savingStageRes == R.string.stage_checking_url }
            awaitTrue { !viewModel.uiState.value.isSaving }
            assertNull(viewModel.uiState.value.savingStageRes)
        }

    @Test
    fun `saving a homepage url shows the page-scan stage while resolving`() =
        runTest {
            val homepageServer = MockWebServer()
            homepageServer.start()
            try {
                homepageServer.enqueue(MockResponse().setHeader("Content-Type", "text/html"))
                homepageServer.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(
                            """<html><body><audio src="${homepageServer.url("/stream.mp3")}"></audio></body></html>""",
                        ),
                )
                homepageServer.enqueue(MockResponse().setResponseCode(200))

                val viewModel = createViewModel(testScheduler)
                viewModel.onNameChange("New FM")
                viewModel.onUrlChange(homepageServer.url("/").toString())

                viewModel.save()

                awaitTrue { viewModel.uiState.value.savingStageRes == R.string.stage_scanning_page }
                awaitTrue { !viewModel.uiState.value.isSaving }
                assertNull(viewModel.uiState.value.savingStageRes)
            } finally {
                homepageServer.shutdown()
            }
        }

    @Test
    fun `pasting a homepage url with no scheme still resolves and saves the stream`() =
        runTest {
            val homepageServer = MockWebServer()
            homepageServer.start()
            try {
                homepageServer.enqueue(MockResponse().setHeader("Content-Type", "text/html"))
                homepageServer.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(
                            """<html><body><audio src="${homepageServer.url("/stream.mp3")}"></audio></body></html>""",
                        ),
                )
                homepageServer.enqueue(MockResponse().setResponseCode(200))

                val viewModel = createViewModel(testScheduler)
                viewModel.onNameChange("New FM")
                // No "http://" prefix - what a non-technical user pastes most often.
                viewModel.onUrlChange(homepageServer.url("/").toString().removePrefix("http://"))

                viewModel.save()

                awaitTrue { viewModel.uiState.value.isSaving }
                awaitTrue { !viewModel.uiState.value.isSaving }
                assertEquals(
                    homepageServer.url("/stream.mp3").toString(),
                    database.radioStationDao().getAllStations()[0].streamUrl,
                )
            } finally {
                homepageServer.shutdown()
            }
        }

    @Test
    fun `saving with a blank name derives one from the resolved homepage's title`() =
        runTest {
            val homepageServer = MockWebServer()
            homepageServer.start()
            try {
                homepageServer.enqueue(MockResponse().setHeader("Content-Type", "text/html"))
                homepageServer.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(
                            """<html><head><title>My Cool Radio</title></head>
                            <body><audio src="${homepageServer.url("/stream.mp3")}"></audio></body></html>""",
                        ),
                )
                homepageServer.enqueue(MockResponse().setResponseCode(200))

                val viewModel = createViewModel(testScheduler)
                viewModel.onUrlChange(homepageServer.url("/").toString())

                viewModel.save()

                awaitTrue { viewModel.uiState.value.isSaving }
                awaitTrue { !viewModel.uiState.value.isSaving }
                assertEquals("My Cool Radio", database.radioStationDao().getAllStations()[0].name)
            } finally {
                homepageServer.shutdown()
            }
        }

    @Test
    fun `saving with a blank name falls back to the stream's own host when nothing better is known`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            viewModel.onUrlChange(server.url("/stream").toString())

            viewModel.save()

            awaitTrue { viewModel.uiState.value.isSaving }
            awaitTrue { !viewModel.uiState.value.isSaving }
            assertEquals("Localhost", database.radioStationDao().getAllStations()[0].name)
        }

    @Test
    fun `saving with a blank name that collides with an existing station gets suffixed`() =
        runTest {
            repository.insertStation(RadioStation(name = "Localhost", streamUrl = "http://example.com/other"))
            val viewModel = createViewModel(testScheduler)
            viewModel.onUrlChange(server.url("/stream").toString())

            viewModel.save()

            awaitTrue { viewModel.uiState.value.isSaving }
            awaitTrue { !viewModel.uiState.value.isSaving }
            assertEquals(
                "Localhost (2)",
                database
                    .radioStationDao()
                    .getAllStations()
                    .first {
                        it.streamUrl ==
                            server
                                .url(
                                    "/stream",
                                ).toString()
                    }.name,
            )
        }

    @Test
    fun `pasting a website with no discoverable stream shows the unreachable error`() =
        runTest {
            val homepageServer = MockWebServer()
            homepageServer.start()
            try {
                homepageServer.enqueue(MockResponse().setHeader("Content-Type", "text/html"))
                homepageServer.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody("<html><body><p>Just a website, no player here.</p></body></html>"),
                )

                val viewModel = createViewModel(testScheduler)
                viewModel.onNameChange("New FM")
                viewModel.onUrlChange(homepageServer.url("/").toString())

                viewModel.save()

                awaitTrue { viewModel.uiState.value.isSaving }
                awaitTrue { !viewModel.uiState.value.isSaving }
                assertEquals(R.string.error_stream_unreachable, viewModel.uiState.value.urlErrorRes)
            } finally {
                homepageServer.shutdown()
            }
        }

    @Test
    fun `editing a station keeps its manual list position after save`() =
        runTest {
            // insertStation() auto-assigns the next sortOrder; add two others first so this one
            // doesn't land at 0 by coincidence and mask a regression.
            repository.insertStation(RadioStation(name = "First", streamUrl = "http://example.com/first"))
            repository.insertStation(RadioStation(name = "Second", streamUrl = "http://example.com/second"))
            val id =
                repository.insertStation(
                    RadioStation(name = "Jazz FM", streamUrl = server.url("/stream").toString()),
                )
            val originalSortOrder = repository.getStationById(id)!!.sortOrder
            val viewModel = createViewModel(testScheduler, editingStationId = id)
            // Wait for init's Room load to populate sortOrder before saving, same reasoning as the
            // icon-replacement test above (Room's suspend calls hop off the virtual test scheduler).
            awaitTrue { viewModel.uiState.value.sortOrder == originalSortOrder }

            viewModel.onDescriptionChange("smooth jazz")
            viewModel.save()

            // See the true->false isSaving reasoning in the test above.
            awaitTrue { viewModel.uiState.value.isSaving }
            awaitTrue { !viewModel.uiState.value.isSaving }
            assertEquals(originalSortOrder, repository.getStationById(id)?.sortOrder)
        }

    @Test
    fun `saving with a replaced image icon deletes the old icon file`() =
        runTest {
            val iconsDir = File(RuntimeEnvironment.getApplication().filesDir, "station_icons").apply { mkdirs() }
            val oldIconFile = File(iconsDir, "old.jpg").apply { writeText("fake-jpeg-bytes") }
            val id =
                repository.insertStation(
                    RadioStation(
                        name = "Jazz FM",
                        streamUrl = server.url("/stream").toString(),
                        customIcon = oldIconFile.absolutePath,
                    ),
                )
            val viewModel = createViewModel(testScheduler, editingStationId = id)
            // Wait for the ViewModel's init block to finish loading the station (including
            // originalCustomIcon, used by save()'s cleanup check) before triggering a save —
            // a single advanceUntilIdle() isn't enough since Room's suspend DAO calls hop onto
            // their own executor thread, off the virtual test scheduler.
            awaitTrue { viewModel.uiState.value.customIcon == oldIconFile.absolutePath }
            assertTrue(oldIconFile.exists())

            viewModel.onEmojiIconSelected("🎷")
            viewModel.save()

            awaitTrue { !oldIconFile.exists() }
        }

    @Test
    fun `saving a resolved homepage downloads its favicon and sets it as customIcon`() =
        runTest {
            val homepageServer = MockWebServer()
            homepageServer.start()
            try {
                homepageServer.enqueue(MockResponse().setHeader("Content-Type", "text/html"))
                homepageServer.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(
                            """
                            <html><head><link rel="icon" href="/favicon.png"></head>
                            <body><audio src="${homepageServer.url("/stream.mp3")}"></audio></body></html>
                            """.trimIndent(),
                        ),
                )
                homepageServer.enqueue(MockResponse().setResponseCode(200))
                homepageServer.enqueue(MockResponse().setBody(Buffer().write(pngBytesFor(64, 64))))

                val viewModel = createViewModel(testScheduler)
                viewModel.onNameChange("New FM")
                viewModel.onUrlChange(homepageServer.url("/").toString())

                viewModel.save()

                // The favicon is now downloaded and decoded before the station is ever written
                // (see AddStationViewModel.save's doc on why it's no longer a fire-and-forget
                // patch), so by the time isSaving flips back to false the row already has it -
                // no separate real-time poll needed here beyond what awaitTrue already does.
                awaitTrue { viewModel.uiState.value.isSaving }
                awaitTrue { !viewModel.uiState.value.isSaving }
                val customIcon = database.radioStationDao().getAllStations()[0].customIcon
                assertTrue(customIcon != null && File(customIcon).exists())
            } finally {
                homepageServer.shutdown()
            }
        }

    @Test
    fun `saving a resolved homepage with no favicon link leaves customIcon null`() =
        runTest {
            val homepageServer = MockWebServer()
            homepageServer.start()
            try {
                homepageServer.enqueue(MockResponse().setHeader("Content-Type", "text/html"))
                homepageServer.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(
                            """<html><body><audio src="${homepageServer.url("/stream.mp3")}"></audio></body></html>""",
                        ),
                )
                homepageServer.enqueue(MockResponse().setResponseCode(200))

                val viewModel = createViewModel(testScheduler)
                viewModel.onNameChange("New FM")
                viewModel.onUrlChange(homepageServer.url("/").toString())

                viewModel.save()

                awaitTrue { viewModel.uiState.value.isSaving }
                awaitTrue { !viewModel.uiState.value.isSaving }
                assertNull(database.radioStationDao().getAllStations()[0].customIcon)
            } finally {
                homepageServer.shutdown()
            }
        }

    @Test
    fun `saving with a manually picked emoji does not overwrite it with a discovered favicon`() =
        runTest {
            val homepageServer = MockWebServer()
            homepageServer.start()
            try {
                homepageServer.enqueue(MockResponse().setHeader("Content-Type", "text/html"))
                homepageServer.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(
                            """
                            <html><head><link rel="icon" href="/favicon.png"></head>
                            <body><audio src="${homepageServer.url("/stream.mp3")}"></audio></body></html>
                            """.trimIndent(),
                        ),
                )
                homepageServer.enqueue(MockResponse().setResponseCode(200))

                val viewModel = createViewModel(testScheduler)
                viewModel.onNameChange("New FM")
                viewModel.onUrlChange(homepageServer.url("/").toString())
                viewModel.onEmojiIconSelected("🎷")

                viewModel.save()

                awaitTrue { viewModel.uiState.value.isSaving }
                awaitTrue { !viewModel.uiState.value.isSaving }
                assertEquals("🎷", database.radioStationDao().getAllStations()[0].customIcon)
                // No favicon request should ever have been fired in this case, so the request
                // count stays exactly at the 3 the main resolve flow made (HEAD, GET, HEAD).
                assertEquals(3, homepageServer.requestCount)
            } finally {
                homepageServer.shutdown()
            }
        }
}
