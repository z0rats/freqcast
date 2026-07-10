package com.urlradiodroid.ui

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the pure decision logic in [RadioPlaybackService] (HLS detection, retry backoff,
 * retryable-error classification) without spinning up the full media session / ExoPlayer / audio
 * framework machinery the service also depends on.
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
    fun `retryDelayMs doubles per attempt and caps at 30s`() {
        assertEquals(2_000L, service.retryDelayMs(1))
        assertEquals(4_000L, service.retryDelayMs(2))
        assertEquals(8_000L, service.retryDelayMs(3))
        assertEquals(16_000L, service.retryDelayMs(4))
        assertEquals(30_000L, service.retryDelayMs(5)) // uncapped would be 32s
        assertEquals(30_000L, service.retryDelayMs(10))
    }

    @Test
    fun `isRetryableNetworkError is true only for IO error codes 2000 through 2010`() {
        assertTrue(service.isRetryableNetworkError(playbackException(2000)))
        assertTrue(service.isRetryableNetworkError(playbackException(2010)))
        assertFalse(service.isRetryableNetworkError(playbackException(1999)))
        assertFalse(service.isRetryableNetworkError(playbackException(2011)))
        assertFalse(service.isRetryableNetworkError(playbackException(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)))
    }

    private fun playbackException(errorCode: Int) = PlaybackException("test", null, errorCode)
}
