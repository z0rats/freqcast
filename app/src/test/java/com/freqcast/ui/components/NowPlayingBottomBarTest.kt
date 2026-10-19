package com.freqcast.ui.components

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.data.RadioStation
import com.freqcast.testing.composeHostRuleChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [NowPlayingBottomBar]: status text, transport/timeshift buttons, and swipe-to-switch neighbors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NowPlayingBottomBarTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val a = RadioStation(id = 1, name = "Alpha", streamUrl = "https://a")
    private val b = RadioStation(id = 2, name = "Bravo", streamUrl = "https://b")
    private val c = RadioStation(id = 3, name = "Charlie", streamUrl = "https://c")

    private val calls = mutableListOf<String>()
    private val switchedTo = mutableListOf<RadioStation>()

    private fun show(
        station: RadioStation? = b,
        stations: List<RadioStation> = listOf(a, b, c),
        status: PlaybackStatus = PlaybackStatus.PLAYING,
        hasTimeshift: Boolean = false,
        isAtLive: Boolean = true,
        trackTitle: String? = null,
        offsetFromLiveMs: Long = 0L,
    ) {
        composeTestRule.setContent {
            NowPlayingBottomBar(
                station = station,
                stations = stations,
                playbackStatus = status,
                hasTimeshift = hasTimeshift,
                isAtLive = isAtLive,
                onPlayPauseClick = { calls += "playPause" },
                onCardClick = { calls += "card" },
                onSwitchStation = { switchedTo += it },
                onRewind5s = { calls += "rewind" },
                onReturnToLive = { calls += "live" },
                trackTitle = trackTitle,
                offsetFromLiveMs = offsetFromLiveMs,
                modifier = Modifier.width(360.dp),
            )
        }
    }

    @Test
    fun `renders nothing without a station`() {
        show(station = null)
        composeTestRule.onNodeWithText("Bravo").assertDoesNotExist()
    }

    @Test
    fun `playing shows the track title and a pause button`() {
        show(trackTitle = "Song A")
        composeTestRule.onNodeWithText("Song A").assertExists()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.pause)).performClick()
        assertEquals(listOf("playPause"), calls)
    }

    @Test
    fun `non-playing statuses show their status word and a play button`() {
        show(status = PlaybackStatus.STARTING, trackTitle = "ignored while not playing")
        composeTestRule.onNodeWithText(context.getString(R.string.starting)).assertExists()
        composeTestRule.onNodeWithText("ignored while not playing").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.play)).assertExists()
    }

    @Test
    fun `timeshift controls are hidden without a buffer`() {
        show(hasTimeshift = false)
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.rewind_5s)).assertDoesNotExist()
    }

    @Test
    fun `at live, timeshift shows rewind and a live marker`() {
        show(hasTimeshift = true, isAtLive = true)
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.rewind_5s)).performClick()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.live)).performClick()
        assertEquals(listOf("rewind", "live"), calls)
    }

    @Test
    fun `behind live shows the offset with a spoken description`() {
        show(hasTimeshift = true, isAtLive = false, offsetFromLiveMs = 75_000)
        composeTestRule.onNodeWithText("−1:15").assertExists()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.behind_live, 1, 15)).assertExists()
    }

    @Test
    fun `tapping the card opens the player`() {
        show()
        composeTestRule.onNodeWithText("Bravo").performClick()
        assertTrue(calls.contains("card"))
    }

    @Test
    fun `swiping left switches to the next station and right to the previous one`() {
        show()
        composeTestRule.onRoot().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(c), switchedTo)

        composeTestRule.onRoot().performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(c, a), switchedTo)
    }

    @Test
    fun `swiping past the last station never switches`() {
        show(station = c)
        composeTestRule.onRoot().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        assertTrue(switchedTo.isEmpty())
        composeTestRule.onNodeWithText("Charlie").assertIsDisplayed()
    }
}
