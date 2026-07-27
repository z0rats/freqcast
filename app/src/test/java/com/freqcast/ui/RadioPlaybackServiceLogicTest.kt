package com.freqcast.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the pure HLS-detection logic in [RadioPlaybackService] without spinning up the full media
 * session / ExoPlayer / audio framework machinery the service also depends on. Retry backoff and
 * retryable-error classification moved to [com.freqcast.ui.playback.ConnectionRetryPolicyTest]
 * along with the rest of the reconnection state machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioPlaybackServiceLogicTest {
    private val service = RadioPlaybackService()

    @Test
    fun `isHlsUrl detects m3u8 case-insensitively`() {
        assertTrue(service.isHlsUrl("https://example.com/stream.m3u8"))
        assertTrue(service.isHlsUrl("https://example.com/STREAM.M3U8"))
        assertTrue(service.isHlsUrl("https://example.com/live?type=m3u8"))
    }

    @Test
    fun `isHlsUrl rejects non-hls urls`() {
        assertFalse(service.isHlsUrl("https://example.com/stream.mp3"))
        assertFalse(service.isHlsUrl("https://example.com/live.aac"))
    }

    @Test
    fun `isHlsUrl prefers the known-hls hint over the url heuristic`() {
        // Directory-confirmed HLS on a URL that doesn't look like it.
        assertTrue(service.isHlsUrl("https://example.com/stream.mp3", knownHls = true))
        // Directory-confirmed non-HLS, even on a URL that would otherwise match the heuristic.
        assertFalse(service.isHlsUrl("https://example.com/stream.m3u8", knownHls = false))
    }

    @Test
    fun `isHlsUrl falls back to the url heuristic when the hint is unknown`() {
        assertTrue(service.isHlsUrl("https://example.com/stream.m3u8", knownHls = null))
        assertFalse(service.isHlsUrl("https://example.com/stream.mp3", knownHls = null))
    }
}
