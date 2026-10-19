package com.freqcast.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.testing.composeHostRuleChain
import com.freqcast.ui.playback.ClipFormat
import com.freqcast.ui.playback.controller.FakePlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * [PlaybackScreen] / [NowPlayingContent] against a [FakePlaybackController]: transport, the
 * timeshift scrubber and rewind row, sleep timer, clip export's preset gating and failure path,
 * copy-on-long-press, and the landscape layout. The clip export success path isn't driven here:
 * it ends in `FileProvider`, and `StationShareTest` must stay the suite's one FileProvider test.
 *
 * Manual main clock: the sleep-timer countdown and the playing glow are both endless loops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlaybackScreenTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val url = "https://jazz.example/s"
    private val calls = mutableListOf<String>()

    private fun show(
        snapshot: PlaybackSnapshot = PlaybackSnapshot(),
        stationName: String? = "Jazz FM",
        configure: FakePlaybackController.() -> Unit = {},
    ): FakePlaybackController {
        val controller = FakePlaybackController(snapshot).apply(configure)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PlaybackScreen(
                stationName = stationName,
                streamUrl = url,
                customIcon = "🎷",
                playbackController = controller,
                onBackClick = { calls += "back" },
                onPlayStopClick = { calls += "playStop" },
            )
        }
        settle()
        return controller
    }

    private fun settle(frames: Int = 10) {
        repeat(frames) {
            composeTestRule.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // Semantic click rather than performScrollTo + a touch: with the main clock paused,
    // performScrollTo's scroll animation never gets a frame and spins forever.
    private fun clickText(text: String) {
        composeTestRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun clickDescription(description: String) {
        composeTestRule.onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private val playing = PlaybackSnapshot(isPlaying = true, currentMediaId = url, trackTitle = "So What")
    private val timeshifting =
        playing.copy(
            hasTimeshift = true,
            isAtLive = false,
            bufferedDurationMs = 6 * 60_000L,
            offsetFromLiveMs = 90_000L,
        )

    @Test
    fun `paused screen shows the station and a play button, and back works`() {
        show()
        composeTestRule.onNodeWithText("🎷").assertExists()
        clickDescription(context.getString(R.string.play))
        clickDescription(context.getString(R.string.back))
        assertEquals(listOf("playStop", "back"), calls)
        composeTestRule.onNodeWithText("So What").assertDoesNotExist()
    }

    @Test
    fun `unknown station name falls back to a placeholder`() {
        show(stationName = null)
        assertTrue(
            composeTestRule
                .onAllNodes(
                    hasText(context.getString(R.string.unknown_station)),
                ).fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    @Test
    fun `playing shows the track title, a pause button and the live badge`() {
        show(playing)
        composeTestRule.onNodeWithText("So What").assertExists()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.pause)).assertExists()
        assertTrue(
            composeTestRule
                .onAllNodes(
                    hasText(context.getString(R.string.live).uppercase()),
                ).fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    @Test
    fun `another stream playing is not shown as this one`() {
        show(playing.copy(currentMediaId = "https://other"))
        composeTestRule.onNodeWithText("So What").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.play)).assertExists()
    }

    @Test
    fun `timeshift rewind segments, go live and the scrubber drive the controller`() {
        val controller = show(timeshifting)
        composeTestRule.onNodeWithText("−1:30").assertExists()

        clickDescription(context.getString(R.string.rewind_seconds, 15))
        clickDescription(context.getString(R.string.rewind_seconds, 60))
        clickDescription(context.getString(R.string.go_live))
        assertEquals(listOf(15_000L, 60_000L), controller.seekBackwardCalls)
        assertEquals(1, controller.seekToLiveCallCount)

        // Scrub to the very start of the 6-minute buffer = 6 minutes behind live.
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.timeshift_slider))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        settle()
        assertEquals(1, controller.seekToOffsetCalls.size)
        assertEquals(6 * 60_000L, controller.seekToOffsetCalls.single())
    }

    @Test
    fun `go live is disabled while already at the live edge`() {
        show(timeshifting.copy(isAtLive = true, offsetFromLiveMs = 0))
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.go_live)).assertIsNotEnabled()
    }

    @Test
    fun `sleep timer presets set and cancel the timer with feedback`() {
        val controller = show(playing)
        clickText(context.getString(R.string.sleep_short))
        clickText(context.getString(R.string.sleep_timer_minutes, 30))
        assertEquals(30, controller.sleepTimerMinutes)
        assertEquals(context.getString(R.string.sleep_timer_set_toast, 30), ShadowToast.getTextOfLatestToast())

        clickText(context.getString(R.string.sleep_short))
        clickText(context.getString(R.string.sleep_timer_off))
        assertEquals(null, controller.sleepTimerMinutes)
        assertEquals(context.getString(R.string.sleep_timer_cancelled_toast), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an active sleep timer shows its countdown`() {
        show(playing.copy(sleepTimerEndAtMs = System.currentTimeMillis() + 10 * 60_000L + 30_000L))
        assertTrue(
            composeTestRule.onAllNodes(hasText("Sleep in 10:", substring = true)).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `clip export only offers presets that fit the buffer, and reports a failure`() {
        show(timeshifting.copy(clipFormatAvailable = true)) {
            clipFormat = ClipFormat.MP3
            exportClipResult = false
        }
        clickText(context.getString(R.string.clip_short))
        composeTestRule.onNodeWithText(context.getString(R.string.export_clip_last_minutes, 5)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.export_clip_last_minutes, 10)).assertDoesNotExist()

        clickText(context.getString(R.string.export_clip_last_minutes, 1))
        assertEquals(context.getString(R.string.export_clip_failed_toast), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `clip export with too little buffer says so`() {
        show(timeshifting.copy(clipFormatAvailable = true, bufferedDurationMs = 20_000L, offsetFromLiveMs = 0)) {
            clipFormat = ClipFormat.MP3
        }
        clickText(context.getString(R.string.clip_short))
        composeTestRule.onNodeWithText(context.getString(R.string.export_clip_not_enough_buffer)).assertExists()
        clickText(context.getString(R.string.close))
        composeTestRule.onNodeWithText(context.getString(R.string.export_clip_not_enough_buffer)).assertDoesNotExist()
    }

    @Test
    fun `clip chip is hidden without a known clip format`() {
        show(timeshifting.copy(clipFormatAvailable = false))
        composeTestRule.onNodeWithText(context.getString(R.string.clip_short)).assertDoesNotExist()
    }

    @Test
    fun `long-pressing the track title copies it`() {
        show(playing)
        composeTestRule
            .onNode(hasText("So What") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        settle()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals(
            "So What",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        assertEquals(context.getString(R.string.track_title_copied), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a connection error is announced`() {
        show(PlaybackSnapshot(currentMediaId = url, isConnectionBroken = true, connectionErrorAt = 1L))
        assertEquals(context.getString(R.string.connection_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    @Config(qualifiers = "land")
    fun `landscape lays art and controls side by side with the same controls`() {
        val controller = show(timeshifting)
        composeTestRule.onNodeWithText("So What").assertExists()
        clickDescription(context.getString(R.string.rewind_seconds, 30))
        assertEquals(listOf(30_000L), controller.seekBackwardCalls)
    }
}
