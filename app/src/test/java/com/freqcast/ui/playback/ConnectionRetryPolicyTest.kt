package com.freqcast.ui.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ConnectionRetryPolicyTest {
    private lateinit var policy: ConnectionRetryPolicy

    @Before
    fun setup() {
        policy = ConnectionRetryPolicy()
    }

    private fun playbackException(errorCode: Int) = PlaybackException("test", null, errorCode)

    @Test
    fun `retryDelayMs doubles per attempt and caps at 30s`() {
        assertEquals(2_000L, ConnectionRetryPolicy.retryDelayMs(1))
        assertEquals(4_000L, ConnectionRetryPolicy.retryDelayMs(2))
        assertEquals(8_000L, ConnectionRetryPolicy.retryDelayMs(3))
        assertEquals(16_000L, ConnectionRetryPolicy.retryDelayMs(4))
        assertEquals(30_000L, ConnectionRetryPolicy.retryDelayMs(5)) // uncapped would be 32s
        assertEquals(30_000L, ConnectionRetryPolicy.retryDelayMs(10))
    }

    @Test
    fun `isRetryableNetworkError is true only for IO error codes 2000 through 2010`() {
        assertTrue(ConnectionRetryPolicy.isRetryableNetworkError(playbackException(2000)))
        assertTrue(ConnectionRetryPolicy.isRetryableNetworkError(playbackException(2010)))
        assertFalse(ConnectionRetryPolicy.isRetryableNetworkError(playbackException(1999)))
        assertFalse(ConnectionRetryPolicy.isRetryableNetworkError(playbackException(2011)))
        assertFalse(
            ConnectionRetryPolicy.isRetryableNetworkError(
                playbackException(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW),
            ),
        )
    }

    @Test
    fun `onPlaybackError on a non-network error gives up immediately`() {
        policy.onPlaybackStarted("https://example.com/stream", knownHls = false)
        val decision = policy.onPlaybackError(playbackException(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertEquals(RetryDecision.GiveUp, decision)
    }

    @Test
    fun `onPlaybackError schedules increasing backoff for consecutive retryable errors`() {
        val attemptId = policy.onPlaybackStarted("https://example.com/stream", knownHls = false)

        val first = policy.onPlaybackError(playbackException(2000)) as RetryDecision.RetryAfter
        assertEquals(2_000L, first.delayMs)
        assertEquals(attemptId, first.attemptId)

        val second = policy.onPlaybackError(playbackException(2000)) as RetryDecision.RetryAfter
        assertEquals(4_000L, second.delayMs)
    }

    @Test
    fun `onPlaybackError gives up after five consecutive retryable errors`() {
        policy.onPlaybackStarted("https://example.com/stream", knownHls = false)
        repeat(5) { assertTrue(policy.onPlaybackError(playbackException(2000)) is RetryDecision.RetryAfter) }
        assertEquals(RetryDecision.GiveUp, policy.onPlaybackError(playbackException(2000)))
    }

    @Test
    fun `onPlaybackSucceeded gives a future failure a fresh retry budget`() {
        policy.onPlaybackStarted("https://example.com/stream", knownHls = false)
        repeat(5) { policy.onPlaybackError(playbackException(2000)) }
        assertEquals(RetryDecision.GiveUp, policy.onPlaybackError(playbackException(2000)))

        policy.onPlaybackSucceeded()

        assertTrue(policy.onPlaybackError(playbackException(2000)) is RetryDecision.RetryAfter)
    }

    @Test
    fun `a fresh non-retry play resets the retry budget but a retry play does not`() {
        policy.onPlaybackStarted("https://example.com/stream", knownHls = false)
        repeat(5) { policy.onPlaybackError(playbackException(2000)) }

        // Retrying the same stream keeps the accumulated count.
        policy.onPlaybackStarted("https://example.com/stream", knownHls = false, isRetry = true)
        assertEquals(RetryDecision.GiveUp, policy.onPlaybackError(playbackException(2000)))

        // A fresh, non-retry play (e.g. the user picked a new station) resets it.
        policy.onPlaybackStarted("https://example.com/other-stream", knownHls = false)
        assertTrue(policy.onPlaybackError(playbackException(2000)) is RetryDecision.RetryAfter)
    }

    @Test
    fun `attemptRetry returns the target while the attempt id is still current`() {
        val attemptId = policy.onPlaybackStarted("https://example.com/stream", knownHls = true)
        val target = policy.attemptRetry(attemptId)
        assertEquals(RetryTarget("https://example.com/stream", true), target)
    }

    @Test
    fun `attemptRetry returns null once a newer attempt has superseded it`() {
        val staleAttemptId = policy.onPlaybackStarted("https://example.com/stream-a", knownHls = false)
        // User switches stations before the stale attempt's delayed retry fires.
        policy.onPlaybackStarted("https://example.com/stream-b", knownHls = false)

        assertNull(policy.attemptRetry(staleAttemptId))
    }

    @Test
    fun `attemptRetry returns null for an attempt that switched away and back to the same url`() {
        val staleAttemptId = policy.onPlaybackStarted("https://example.com/stream-a", knownHls = false)
        policy.onPlaybackStarted("https://example.com/stream-b", knownHls = false)
        // Switches back to the same URL as the stale attempt - a stream-url equality guard alone
        // would wrongly treat this as still current; the attempt id must not.
        policy.onPlaybackStarted("https://example.com/stream-a", knownHls = false)

        assertNull(policy.attemptRetry(staleAttemptId))
    }

    @Test
    fun `reset invalidates any in-flight retry and clears pending state`() {
        val attemptId = policy.onPlaybackStarted("https://example.com/stream", knownHls = false)
        policy.onPlaybackError(playbackException(2000))
        assertTrue(policy.isPendingRetry())

        policy.reset()

        assertFalse(policy.isPendingRetry())
        assertNull(policy.attemptRetry(attemptId))
        assertNull(policy.currentStreamUrlOrNull())
    }

    @Test
    fun `onNetworkAvailable retries immediately when a retry is pending`() {
        val attemptId = policy.onPlaybackStarted("https://example.com/stream", knownHls = false)
        policy.onPlaybackError(playbackException(2000)) // sets pendingRetry

        val decision = policy.onNetworkAvailable(isPlayerIdle = false)

        assertTrue(decision is RetryDecision.RetryNow)
        assertEquals(attemptId, (decision as RetryDecision.RetryNow).attemptId)
    }

    @Test
    fun `onNetworkAvailable retries when the player is idle even without a pending flag`() {
        policy.onPlaybackStarted("https://example.com/stream", knownHls = false)
        assertTrue(policy.onNetworkAvailable(isPlayerIdle = true) is RetryDecision.RetryNow)
    }

    @Test
    fun `onNetworkAvailable is a no-op when nothing is pending and the player isn't idle`() {
        policy.onPlaybackStarted("https://example.com/stream", knownHls = false)
        assertEquals(RetryDecision.NoAction, policy.onNetworkAvailable(isPlayerIdle = false))
    }

    @Test
    fun `onNetworkAvailable is a no-op before anything has ever played`() {
        assertEquals(RetryDecision.NoAction, policy.onNetworkAvailable(isPlayerIdle = true))
    }

    @Test
    fun `onNetworkAvailable gives up once the retry budget is exhausted`() {
        policy.onPlaybackStarted("https://example.com/stream", knownHls = false)
        repeat(5) { policy.onPlaybackError(playbackException(2000)) }
        assertEquals(RetryDecision.GiveUp, policy.onNetworkAvailable(isPlayerIdle = true))
    }
}
