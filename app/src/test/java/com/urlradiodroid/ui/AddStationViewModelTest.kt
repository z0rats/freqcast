package com.urlradiodroid.ui

import androidx.room.Room
import com.urlradiodroid.data.AppDatabase
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import com.urlradiodroid.util.StreamValidator
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
import java.io.File

/**
 * [StreamValidator] hops onto the real `Dispatchers.IO` for its MockWebServer round-trip, off the
 * virtual test scheduler — so, like [MainViewModelTest], assertions after [AddStationViewModel.save]
 * use [awaitTrue] (a real-time poll) rather than a single [advanceUntilIdle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
    ): AddStationViewModel {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        return AddStationViewModel(repository, editingStationId, StreamValidator(client = OkHttpClient()))
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

            viewModel.onImageIconSelected("/data/user/0/com.urlradiodroid/files/station_icons/a.jpg")

            assertEquals("/data/user/0/com.urlradiodroid/files/station_icons/a.jpg", viewModel.uiState.value.customIcon)
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
}
