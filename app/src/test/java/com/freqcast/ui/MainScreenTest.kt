package com.freqcast.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioBrowserApi
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.testing.awaitCondition
import com.freqcast.testing.composeHostRuleChain
import com.freqcast.testing.inMemoryDatabase
import com.freqcast.ui.playback.SettingsStore
import com.freqcast.ui.playback.controller.FakePlaybackController
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowToast

/**
 * [MainScreen] over a real [MainViewModel] + in-memory Room and a [FakePlaybackController]:
 * the list, the header actions, play/stop routing (incl. the metered-connection prompt), syncing
 * to playback started elsewhere, the mini player, delete + Undo, search and its directory fallback,
 * and the wide-screen two-pane layout.
 *
 * The main clock is driven by hand: [MainScreen]'s list mounts `rememberDragDropState`, whose
 * per-frame loop never lets an auto-advancing clock go idle (see `docs/architecture/testing-ci.md`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MainScreenTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var server: MockWebServer
    private lateinit var viewModel: MainViewModel
    private val settingsStore by lazy { SettingsStore(context) }

    private val calls = mutableListOf<String>()

    private val jazz = RadioStation(name = "Jazz FM", streamUrl = "https://jazz.example/s", customIcon = "🎷")
    private val rock = RadioStation(name = "Rock FM", streamUrl = "https://rock.example/s", customIcon = "🎸")

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = RadioStationRepository(database.radioStationDao())
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody(
                        """[{"stationuuid":"u1","name":"Directory Blues","url":"https://blues.example/s","tags":"blues"}]""",
                    )
            }
        server.start()
        settingsStore.warnOnMeteredConnection = false
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private fun show(
        vararg stations: RadioStation,
        controller: FakePlaybackController? = FakePlaybackController(),
        deleteThroughViewModel: Boolean = false,
    ) {
        runBlocking { stations.forEach { repository.insertStation(it) } }
        viewModel = MainViewModel(repository, settingsStore, RadioBrowserApi(baseUrl = server.url("/")))
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            MainScreen(
                viewModel = viewModel,
                onAddStationClick = { calls += "add" },
                onPlayStation = { station ->
                    calls += "play:${station.name}"
                    runBlocking { controller?.toggle(station) }
                    viewModel.updateCurrentPlayingStation(station.id)
                },
                onNowPlayingClick = { calls += "open:${it.name}" },
                playbackController = controller,
                onResume = { viewModel.loadStations() },
                stationListActions =
                    StationListActions(
                        onDiscoverStationsClick = { calls += "discover" },
                        onAlarmClick = { calls += "alarms" },
                        onSettingsClick = { calls += "settings" },
                        onStationEdit = { calls += "edit:${it.name}" },
                        onStationDelete = {
                            calls += "delete:${it.name}"
                            if (deleteThroughViewModel) viewModel.deleteStation(it)
                        },
                        onStopPlayback = {
                            calls += "stop"
                            controller?.stopPlayback()
                        },
                    ),
            )
        }
        settle()
    }

    /** Advances a few frames and drains the main looper - the stand-in for an auto-advancing clock. */
    private fun settle(frames: Int = 10) {
        repeat(frames) {
            composeTestRule.mainClock.advanceTimeByFrame()
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        composeTestRule.waitForIdle()
    }

    private fun awaitText(text: String) =
        composeTestRule.awaitCondition {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun stationNames() = runBlocking { repository.getAllStations().map { it.name } }

    @Test
    fun `empty library shows the welcome message, and the header and fab actions fire`() {
        show()
        composeTestRule.onNodeWithText(context.getString(R.string.no_stations)).assertExists()

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.add_station)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.discover_stations)).performClick()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        settle()
        composeTestRule.onNodeWithText(context.getString(R.string.alarms)).performClick()
        settle()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        settle()
        composeTestRule.onNodeWithText(context.getString(R.string.settings)).performClick()

        assertEquals(listOf("add", "discover", "alarms", "settings"), calls)
    }

    @Test
    fun `tapping play starts the station and shows the mini player, tapping again stops it`() {
        show(jazz, rock)
        composeTestRule.onAllNodesWithContentDescription(context.getString(R.string.play))[0].performClick()
        settle()
        assertEquals(listOf("play:Jazz FM"), calls)
        assertEquals(2, composeTestRule.onAllNodes(hasText("Jazz FM")).fetchSemanticsNodes().size) // row + mini player

        composeTestRule.onAllNodesWithContentDescription(context.getString(R.string.pause))[0].performClick()
        settle()
        assertEquals(listOf("play:Jazz FM", "stop"), calls)
    }

    @Test
    fun `playback started elsewhere is picked up as the current station`() {
        val controller = FakePlaybackController()
        show(jazz, rock, controller = controller)
        controller.emit(PlaybackSnapshot(isPlaying = true, currentMediaId = rock.streamUrl, trackTitle = "Riff"))
        settle()

        val rockId = runBlocking { repository.getAllStations() }.first { it.name == "Rock FM" }.id
        assertEquals(rockId, viewModel.getCurrentPlayingStationId())
        assertTrue(composeTestRule.onAllNodes(hasText("Riff")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `mini player opens the full player and drives timeshift on the controller`() {
        val controller = FakePlaybackController()
        show(jazz, controller = controller)
        controller.emit(
            PlaybackSnapshot(isPlaying = true, currentMediaId = jazz.streamUrl, hasTimeshift = true, isAtLive = true),
        )
        settle()

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.rewind_5s)).performClick()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.live)).performClick()
        assertEquals(listOf(RadioPlaybackService.TIMESHIFT_SEEK_BACK_MS), controller.seekBackwardCalls)
        assertEquals(1, controller.seekToLiveCallCount)
    }

    @Test
    fun `a connection error is announced once`() {
        val controller = FakePlaybackController()
        show(jazz, controller = controller)
        controller.emit(
            PlaybackSnapshot(currentMediaId = jazz.streamUrl, connectionErrorAt = 42L, isConnectionBroken = true),
        )
        settle()
        assertEquals(context.getString(R.string.connection_failed), ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    fun `on a metered connection with the warning on, play asks first`() {
        settingsStore.warnOnMeteredConnection = true
        val cm = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        cm.setDefaultNetworkActive(true)
        cm.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_MOBILE,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            ),
        )
        show(jazz)

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.play)).performClick()
        settle()
        composeTestRule.onNodeWithText(context.getString(R.string.metered_connection_dialog_message)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        settle()
        assertTrue(calls.isEmpty())

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.play)).performClick()
        settle()
        composeTestRule.onNodeWithText(context.getString(R.string.metered_connection_continue)).performClick()
        settle()
        assertEquals(listOf("play:Jazz FM"), calls)
    }

    @Test
    fun `deleting from the swipe row removes it, and Undo puts it back`() {
        show(jazz, rock, deleteThroughViewModel = true)
        composeTestRule.onNodeWithText("Rock FM").performTouchInput { swipeLeft() }
        settle(30)
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
        settle()
        assertEquals(listOf("Jazz FM"), stationNames())

        awaitText(context.getString(R.string.undo))
        composeTestRule.onNodeWithText(context.getString(R.string.undo)).performClick()
        settle()
        assertEquals(listOf("Jazz FM", "Rock FM"), stationNames())
    }

    @Test
    fun `swipe row edit reports the station`() {
        show(jazz)
        composeTestRule.onNodeWithText("Jazz FM").performTouchInput { swipeLeft() }
        settle(30)
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.edit)).performClick()
        assertEquals(listOf("edit:Jazz FM"), calls)
    }

    @Test
    fun `with more than four stations a search field filters the list, falling back to the directory`() {
        val many =
            (1..5).map {
                RadioStation(
                    name = "Station $it",
                    streamUrl = "https://s$it.example/s",
                    customIcon = "📻",
                )
            }
        show(*many.toTypedArray())

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("Station 3")
        settle()
        // "Station 3" is also the search field's own text - look for list rows only.
        composeTestRule.onNode(hasText("Station 3") and !hasSetTextAction()).assertExists()
        composeTestRule.onNodeWithText("Station 1").assertDoesNotExist()

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("blues")
        settle()
        composeTestRule.onNodeWithText(context.getString(R.string.no_search_results)).assertExists()
        awaitText("Directory Blues")

        composeTestRule.onNodeWithText(context.getString(R.string.discover_add)).performClick()
        composeTestRule.awaitCondition { "Directory Blues" in stationNames() }
    }

    @Test
    @Config(qualifiers = "w900dp-h600dp")
    fun `wide screen shows a detail pane instead of the mini player`() {
        val controller = FakePlaybackController()
        show(jazz, controller = controller)
        composeTestRule.onNodeWithText(context.getString(R.string.no_station_selected)).assertExists()

        controller.emit(PlaybackSnapshot(isPlaying = true, currentMediaId = jazz.streamUrl))
        settle()
        composeTestRule.onNodeWithText(context.getString(R.string.no_station_selected)).assertDoesNotExist()
    }
}
