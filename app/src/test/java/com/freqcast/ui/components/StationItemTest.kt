package com.freqcast.ui.components

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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

/**
 * [StationItem]'s behaviour: the subtitle's status/track/description precedence, the play button,
 * the swipe-to-reveal action row (Edit hidden for curated stations, ADR-0054), and the one-time
 * swipe hint. Visual styling is covered separately by `StationItemScreenshotTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StationItemTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val station =
        RadioStation(
            id = 1,
            name = "Jazz FM",
            streamUrl = "https://jazz.example/s",
            customIcon = "🎷",
            description = "smooth jazz",
        )

    private val calls = mutableListOf<String>()

    private fun show(
        station: RadioStation = this.station,
        isActive: Boolean = false,
        isPlaying: Boolean = false,
        isStarting: Boolean = false,
        isStartError: Boolean = false,
        trackTitle: String? = null,
        playSwipeHint: Boolean = false,
    ) {
        composeTestRule.setContent {
            StationItem(
                station = station,
                isActive = isActive,
                isPlaying = isPlaying,
                isStarting = isStarting,
                isStartError = isStartError,
                trackTitle = trackTitle,
                playSwipeHint = playSwipeHint,
                onSwipeHintConsumed = { calls += "hintConsumed" },
                onPlayClick = { calls += "play" },
                onEditClick = { calls += "edit" },
                onDeleteClick = { calls += "delete" },
                onShareClick = { calls += "share" },
            )
        }
    }

    @Test
    fun `idle row shows the description as its subtitle`() {
        show()
        composeTestRule.onNodeWithText("Jazz FM").assertIsDisplayed()
        composeTestRule.onNodeWithText("smooth jazz").assertIsDisplayed()
    }

    @Test
    fun `blank description falls back to the stream url`() {
        show(station = station.copy(description = "  "))
        composeTestRule.onNodeWithText("https://jazz.example/s").assertIsDisplayed()
    }

    @Test
    fun `playing row shows the live track title instead of the description`() {
        show(isActive = true, isPlaying = true, trackTitle = "Miles Davis - So What")
        composeTestRule.onNodeWithText("Miles Davis - So What").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.pause)).assertIsDisplayed()
    }

    @Test
    fun `playing row without a track title says playing`() {
        show(isActive = true, isPlaying = true)
        composeTestRule.onNodeWithText(context.getString(R.string.playing)).assertIsDisplayed()
    }

    @Test
    fun `active starting and failed rows show their status`() {
        show(isActive = true, isStarting = true)
        composeTestRule.onNodeWithText(context.getString(R.string.starting)).assertIsDisplayed()
    }

    @Test
    fun `active failed row says connection failed`() {
        show(isActive = true, isStartError = true)
        composeTestRule.onNodeWithText(context.getString(R.string.connection_failed)).assertIsDisplayed()
    }

    @Test
    fun `starting flag on an inactive row is ignored`() {
        show(isActive = false, isStarting = true, isPlaying = true, trackTitle = "x")
        composeTestRule.onNodeWithText("smooth jazz").assertIsDisplayed()
    }

    @Test
    fun `tapping play invokes the play callback`() {
        show()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.play)).performClick()
        assertEquals(listOf("play"), calls)
    }

    @Test
    fun `swiping left reveals edit, share and delete, each of which fires and closes the row`() {
        show()
        swipeOpen()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.edit)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("edit"), calls)

        swipeOpen()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.share_station)).performClick()
        swipeOpen()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
        assertEquals(listOf("edit", "share", "delete"), calls)
    }

    @Test
    fun `play tap while revealed closes the row instead of playing`() {
        show()
        swipeOpen()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.play)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(calls.isEmpty())

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.play)).performClick()
        assertEquals(listOf("play"), calls)
    }

    @Test
    fun `curated station shows a badge and no edit action`() {
        show(station = station.copy(isCurated = true))
        composeTestRule.onNodeWithText(context.getString(R.string.station_curated_badge)).assertIsDisplayed()
        swipeOpen()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.share_station)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.edit)).assertDoesNotExist()
    }

    @Test
    fun `swipe hint plays once and reports itself consumed`() {
        show(playSwipeHint = true)
        composeTestRule.mainClock.advanceTimeBy(6_000)
        composeTestRule.waitForIdle()
        assertEquals(listOf("hintConsumed"), calls)
    }

    private fun swipeOpen() {
        composeTestRule.onNodeWithText("Jazz FM").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
    }
}
