package com.freqcast.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPresentationTest {
    @Test
    fun `playing wins over every other signal`() {
        assertEquals(
            PlaybackStatus.PLAYING,
            computePlaybackStatus(
                isPlaying = true,
                isBuffering = true,
                isRetryPending = true,
                isConnectionBroken = true,
            ),
        )
        assertEquals(
            PlaybackStatus.PLAYING,
            computePlaybackStatus(
                isPlaying = true,
                isBuffering = false,
                isRetryPending = false,
                isConnectionBroken = false,
            ),
        )
    }

    @Test
    fun `connection broken is error when not playing, even mid-buffer or mid-retry`() {
        assertEquals(
            PlaybackStatus.ERROR,
            computePlaybackStatus(
                isPlaying = false,
                isBuffering = false,
                isRetryPending = false,
                isConnectionBroken = true,
            ),
        )
        assertEquals(
            PlaybackStatus.ERROR,
            computePlaybackStatus(
                isPlaying = false,
                isBuffering = true,
                isRetryPending = true,
                isConnectionBroken = true,
            ),
        )
    }

    @Test
    fun `buffering or a pending retry within budget is starting, not error`() {
        assertEquals(
            PlaybackStatus.STARTING,
            computePlaybackStatus(
                isPlaying = false,
                isBuffering = true,
                isRetryPending = false,
                isConnectionBroken = false,
            ),
        )
        assertEquals(
            PlaybackStatus.STARTING,
            computePlaybackStatus(
                isPlaying = false,
                isBuffering = false,
                isRetryPending = true,
                isConnectionBroken = false,
            ),
        )
    }

    @Test
    fun `paused when idle with no error and no retry`() {
        assertEquals(
            PlaybackStatus.PAUSED,
            computePlaybackStatus(
                isPlaying = false,
                isBuffering = false,
                isRetryPending = false,
                isConnectionBroken = false,
            ),
        )
    }
}
