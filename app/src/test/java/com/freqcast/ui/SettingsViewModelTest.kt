package com.freqcast.ui

import androidx.room.Room
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.UpdateChecker
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class SettingsViewModelTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
                .allowMainThreadQueries()
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

    private fun createViewModel(
        scheduler: TestCoroutineScheduler,
        currentVersion: String = "3.4.3",
    ): SettingsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        return SettingsViewModel(
            repository,
            currentVersion,
            UpdateChecker(releaseUrl = server.url("/").toString()),
        )
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
    fun `uiState exposes the current app version immediately`() =
        runTest {
            val viewModel = createViewModel(testScheduler, currentVersion = "3.4.3")
            assertEquals("3.4.3", viewModel.uiState.value.currentVersion)
        }

    @Test
    fun `a newer GitHub release marks an update as available`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"tag_name": "v3.5.0", "html_url": "https://github.com/z0rats/freqcast/releases/tag/v3.5.0"}""",
                ),
            )
            val viewModel = createViewModel(testScheduler, currentVersion = "3.4.3")

            awaitTrue { viewModel.uiState.value.updateStatus != UpdateStatus.UNKNOWN }

            assertEquals(UpdateStatus.AVAILABLE, viewModel.uiState.value.updateStatus)
            assertEquals("https://github.com/z0rats/freqcast/releases/tag/v3.5.0", viewModel.uiState.value.updateUrl)
        }

    @Test
    fun `an up-to-date GitHub release reports UP_TO_DATE with no update url`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"tag_name": "v3.4.3", "html_url": "https://github.com/z0rats/freqcast/releases/tag/v3.4.3"}""",
                ),
            )
            val viewModel = createViewModel(testScheduler, currentVersion = "3.4.3")

            awaitTrue { viewModel.uiState.value.updateStatus != UpdateStatus.UNKNOWN }

            assertEquals(UpdateStatus.UP_TO_DATE, viewModel.uiState.value.updateStatus)
            assertNull(viewModel.uiState.value.updateUrl)
        }

    @Test
    fun `a failed update check leaves updateStatus UNKNOWN`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            val viewModel = createViewModel(testScheduler, currentVersion = "3.4.3")

            advanceUntilIdle()

            assertEquals(UpdateStatus.UNKNOWN, viewModel.uiState.value.updateStatus)
            assertNull(viewModel.uiState.value.updateUrl)
        }
}
