package com.freqcast.ui.playback.controller

import com.freqcast.data.RadioStation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises [FakePlaybackController] itself so future MainScreen/PlaybackScreen tests can rely on
 * it behaving like the real seam - toggle/stop/seek go through [PlaybackController], not a raw
 * `RadioPlaybackService`.
 */
class FakePlaybackControllerTest {
    private val station = RadioStation(name = "Test FM", streamUrl = "https://example.com/stream")

    @Test
    fun `toggle starts a station and updates the snapshot`() =
        runTest {
            val controller = FakePlaybackController()

            val result = controller.toggle(station)

            assertEquals(ToggleResult.STARTED, result)
            assertEquals(listOf(station), controller.toggledStations)
            assertEquals(station.streamUrl, controller.playbackSnapshot.value.currentMediaId)
            assertEquals(true, controller.playbackSnapshot.value.isPlaying)
        }

    @Test
    fun `toggling the same station again stops it`() =
        runTest {
            val controller = FakePlaybackController()
            controller.toggle(station)

            val result = controller.toggle(station)

            assertEquals(ToggleResult.STOPPED, result)
            assertEquals(1, controller.stopPlaybackCallCount)
            assertEquals(false, controller.playbackSnapshot.value.isPlaying)
        }

    @Test
    fun `toggle without network reports unavailable and does not start`() =
        runTest {
            val controller = FakePlaybackController(isNetworkAvailable = false)

            val result = controller.toggle(station)

            assertEquals(ToggleResult.NETWORK_UNAVAILABLE, result)
            assertEquals(emptyList<RadioStation>(), controller.toggledStations)
        }

    @Test
    fun `cancelSleepTimer clears a previously set timer`() {
        val controller = FakePlaybackController()
        controller.setSleepTimer(30)

        controller.cancelSleepTimer()

        assertNull(controller.sleepTimerMinutes)
    }
}
