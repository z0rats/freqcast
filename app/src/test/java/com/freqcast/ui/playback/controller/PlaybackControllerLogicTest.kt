package com.freqcast.ui.playback.controller

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [decideToggleAction], the pure decision behind every toggle call site (MainActivity,
 * PlaybackActivity, NowPlayingBottomBar). In particular, stopping the currently playing station
 * must not require network - only starting a new one does.
 */
class PlaybackControllerLogicTest {
    private val streamA = "https://a.example/stream"
    private val streamB = "https://b.example/stream"

    @Test
    fun `stops when this station is currently playing`() {
        assertEquals(
            ToggleAction.STOP,
            decideToggleAction(
                stationStreamUrl = streamA,
                currentMediaId = streamA,
                isPlaying = true,
                isNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun `starts a different station even while another is playing`() {
        assertEquals(
            ToggleAction.START,
            decideToggleAction(
                stationStreamUrl = streamB,
                currentMediaId = streamA,
                isPlaying = true,
                isNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun `starts when nothing is playing`() {
        assertEquals(
            ToggleAction.START,
            decideToggleAction(
                stationStreamUrl = streamA,
                currentMediaId = null,
                isPlaying = false,
                isNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun `does not start when this stream is loaded but not yet playing`() {
        assertEquals(
            ToggleAction.START,
            decideToggleAction(
                stationStreamUrl = streamA,
                currentMediaId = streamA,
                isPlaying = false,
                isNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun `rejects starting without network`() {
        assertEquals(
            ToggleAction.REJECT_NO_NETWORK,
            decideToggleAction(
                stationStreamUrl = streamA,
                currentMediaId = null,
                isPlaying = false,
                isNetworkAvailable = false,
            ),
        )
    }

    @Test
    fun `stopping the currently playing station does not require network`() {
        assertEquals(
            ToggleAction.STOP,
            decideToggleAction(
                stationStreamUrl = streamA,
                currentMediaId = streamA,
                isPlaying = true,
                isNetworkAvailable = false,
            ),
        )
    }
}
