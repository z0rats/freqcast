package com.freqcast.ui.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.testing.composeHostRuleChain
import com.freqcast.ui.PlaybackSnapshot
import com.freqcast.ui.playback.controller.FakePlaybackController
import com.freqcast.ui.playback.controller.PlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The `isCurrent` scoping in [rememberPlaybackPresentation] (which fields are filtered to the
 * current stream and which deliberately aren't), [rememberRawPlaybackState]'s pass-through, and
 * [ConnectionErrorToastEffect]'s timestamp-based dedup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlaybackPresentationComposeTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val stationUrl = "https://example.com/a"
    private val otherUrl = "https://example.com/b"

    private val playingA =
        PlaybackSnapshot(
            isPlaying = true,
            isBuffering = true,
            currentMediaId = stationUrl,
            hasTimeshift = true,
            isAtLive = false,
            trackTitle = "Song",
            sleepTimerEndAtMs = 123L,
            connectionErrorAt = 7L,
            bufferedDurationMs = 5_000L,
            offsetFromLiveMs = 1_000L,
            clipFormatAvailable = true,
        )

    private fun present(
        controller: PlaybackController?,
        currentUrl: String?,
    ): () -> PlaybackPresentation {
        lateinit var latest: PlaybackPresentation
        composeTestRule.setContent { latest = rememberPlaybackPresentation(controller, currentUrl) }
        composeTestRule.waitForIdle()
        return { latest }
    }

    @Test
    fun `no controller yields the default presentation`() {
        val p = present(null, stationUrl)
        assertEquals(PlaybackPresentation(), p())
    }

    @Test
    fun `snapshot for the current stream is mirrored in full`() {
        val p = present(FakePlaybackController(playingA), stationUrl)()
        assertTrue(p.isPlaying)
        assertTrue(p.isBuffering)
        assertTrue(p.hasTimeshift)
        assertFalse(p.isAtLive)
        assertEquals("Song", p.trackTitle)
        assertEquals(123L, p.sleepTimerEndAtMs)
        assertEquals(7L, p.connectionErrorAt)
        assertEquals(5_000L, p.bufferedDurationMs)
        assertEquals(1_000L, p.offsetFromLiveMs)
        assertTrue(p.clipFormatAvailable)
        assertEquals(PlaybackStatus.PLAYING, p.status)
    }

    @Test
    fun `a different stream playing is scoped out but service-level fields pass through`() {
        val p = present(FakePlaybackController(playingA), otherUrl)()
        assertFalse(p.isPlaying)
        assertFalse(p.isBuffering)
        assertFalse(p.hasTimeshift)
        assertNull(p.trackTitle)
        assertEquals(PlaybackStatus.PAUSED, p.status)
        // Deliberately unscoped (see rememberPlaybackPresentation's comments)
        assertEquals(7L, p.connectionErrorAt)
        assertEquals(123L, p.sleepTimerEndAtMs)
        assertEquals(5_000L, p.bufferedDurationMs)
        assertTrue(p.clipFormatAvailable)
    }

    @Test
    fun `null current url matches any stream`() {
        val p = present(FakePlaybackController(playingA), null)()
        assertTrue(p.isPlaying)
        assertEquals("Song", p.trackTitle)
    }

    @Test
    fun `retry pending and broken connection feed status`() {
        val controller = FakePlaybackController(PlaybackSnapshot(currentMediaId = stationUrl, isRetryPending = true))
        val p = present(controller, stationUrl)
        assertEquals(PlaybackStatus.STARTING, p().status)

        controller.emit(PlaybackSnapshot(currentMediaId = stationUrl, isConnectionBroken = true))
        composeTestRule.waitForIdle()
        assertEquals(PlaybackStatus.ERROR, p().status)
    }

    @Test
    fun `raw state passes media id and isPlaying through unscoped`() {
        val controller = FakePlaybackController(playingA.copy(currentMediaId = otherUrl))
        lateinit var latest: RawPlaybackState
        var current: PlaybackController? by mutableStateOf(controller)
        composeTestRule.setContent { latest = rememberRawPlaybackState(current) }
        composeTestRule.waitForIdle()
        assertEquals(RawPlaybackState(currentMediaId = otherUrl, isPlaying = true), latest)

        current = null
        composeTestRule.waitForIdle()
        assertEquals(RawPlaybackState(), latest)
    }

    @Test
    fun `connection error toast shows once per distinct timestamp`() {
        var errorAt: Long? by mutableStateOf(null)
        composeTestRule.setContent { ConnectionErrorToastEffect(errorAt) }
        composeTestRule.waitForIdle()
        assertEquals(0, ShadowToast.shownToastCount())

        errorAt = 100L
        composeTestRule.waitForIdle()
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.connection_failed),
            ShadowToast.getTextOfLatestToast(),
        )

        errorAt = 200L
        composeTestRule.waitForIdle()
        assertEquals(2, ShadowToast.shownToastCount())
    }
}
