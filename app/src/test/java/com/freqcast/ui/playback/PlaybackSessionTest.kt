package com.freqcast.ui.playback

import androidx.media3.common.PlaybackException
import com.freqcast.ui.PlaybackRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for PlaybackException's constructor (media3 model classes throw "not mocked"
// on the plain Android stub jar) - PlaybackSession itself has no Android dependency.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlaybackSessionTest {
    private var now = 1_000L
    private lateinit var session: PlaybackSession

    @Before
    fun setup() {
        session = PlaybackSession(clock = { now })
    }

    private fun playbackException(errorCode: Int) = PlaybackException("test", null, errorCode)

    private fun request(
        url: String = "https://example.com/stream",
        knownHls: Boolean? = false,
    ) = PlaybackRequest(stationName = "Test FM", streamUrl = url, customIcon = null, knownHls = knownHls)

    private fun exhaustRetries() {
        repeat(5) { assertTrue(session.onPlaybackError(playbackException(2000)) is RetryDecision.RetryAfter) }
    }

    @Test
    fun `retryDelayMs doubles per attempt and caps at 30s`() {
        assertEquals(2_000L, PlaybackSession.retryDelayMs(1))
        assertEquals(4_000L, PlaybackSession.retryDelayMs(2))
        assertEquals(8_000L, PlaybackSession.retryDelayMs(3))
        assertEquals(16_000L, PlaybackSession.retryDelayMs(4))
        assertEquals(30_000L, PlaybackSession.retryDelayMs(5)) // uncapped would be 32s
        assertEquals(30_000L, PlaybackSession.retryDelayMs(10))
    }

    @Test
    fun `isRetryableNetworkError is true only for IO error codes 2000 through 2010`() {
        assertTrue(PlaybackSession.isRetryableNetworkError(playbackException(2000)))
        assertTrue(PlaybackSession.isRetryableNetworkError(playbackException(2010)))
        assertFalse(PlaybackSession.isRetryableNetworkError(playbackException(1999)))
        assertFalse(PlaybackSession.isRetryableNetworkError(playbackException(2011)))
        assertFalse(
            PlaybackSession.isRetryableNetworkError(
                playbackException(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW),
            ),
        )
    }

    @Test
    fun `onPlaybackError on a non-network error gives up immediately and marks the session broken`() {
        session.onPlaybackStarted(request())

        val decision = session.onPlaybackError(playbackException(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))

        assertEquals(RetryDecision.GiveUp, decision)
        assertTrue(session.isConnectionBroken)
        assertEquals(now, session.connectionErrorAt)
    }

    @Test
    fun `onPlaybackError schedules increasing backoff for consecutive retryable errors`() {
        val attemptId = session.onPlaybackStarted(request())

        val first = session.onPlaybackError(playbackException(2000)) as RetryDecision.RetryAfter
        assertEquals(2_000L, first.delayMs)
        assertEquals(attemptId, first.attemptId)
        assertTrue(session.isPendingRetry)
        // Backing off isn't a failure yet - neither error signal fires.
        assertNull(session.connectionErrorAt)
        assertFalse(session.isConnectionBroken)

        val second = session.onPlaybackError(playbackException(2000)) as RetryDecision.RetryAfter
        assertEquals(4_000L, second.delayMs)
    }

    @Test
    fun `onPlaybackError gives up after five consecutive retryable errors`() {
        session.onPlaybackStarted(request())
        exhaustRetries()
        assertEquals(RetryDecision.GiveUp, session.onPlaybackError(playbackException(2000)))
        assertTrue(session.isConnectionBroken)
    }

    @Test
    fun `connectionErrorAt changes on each give-up and survives a stop`() {
        session.onPlaybackStarted(request())
        session.onPlaybackError(playbackException(1))
        val first = session.connectionErrorAt
        session.onStopped()
        assertEquals(first, session.connectionErrorAt)

        now += 5
        session.onPlaybackStarted(request())
        session.onPlaybackError(playbackException(1))

        assertEquals(now, session.connectionErrorAt)
    }

    @Test
    fun `isConnectionBroken survives a stop but clears on the next play`() {
        session.onPlaybackStarted(request())
        session.onPlaybackError(playbackException(1))
        session.onStopped()
        assertTrue(session.isConnectionBroken)

        session.onPlaybackStarted(request())

        assertFalse(session.isConnectionBroken)
    }

    @Test
    fun `onPlaybackSucceeded gives a future failure a fresh retry budget`() {
        session.onPlaybackStarted(request())
        exhaustRetries()

        session.onPlaybackSucceeded()

        assertTrue(session.onPlaybackError(playbackException(2000)) is RetryDecision.RetryAfter)
    }

    @Test
    fun `a fresh non-retry play resets the retry budget but a retry play does not`() {
        session.onPlaybackStarted(request())
        exhaustRetries()

        // Retrying the same stream keeps the accumulated count.
        session.onPlaybackStarted(request(), isRetry = true)
        assertEquals(RetryDecision.GiveUp, session.onPlaybackError(playbackException(2000)))

        // A fresh, non-retry play (e.g. the user picked a new station) resets it.
        session.onPlaybackStarted(request("https://example.com/other-stream"))
        assertTrue(session.onPlaybackError(playbackException(2000)) is RetryDecision.RetryAfter)
    }

    @Test
    fun `attemptRetry replays the whole request while the attempt id is still current`() {
        val original = request(knownHls = true)
        val attemptId = session.onPlaybackStarted(original)

        assertEquals(original, session.attemptRetry(attemptId))
    }

    @Test
    fun `attemptRetry returns null once a newer attempt has superseded it`() {
        val staleAttemptId = session.onPlaybackStarted(request("https://example.com/stream-a"))
        // User switches stations before the stale attempt's delayed retry fires.
        session.onPlaybackStarted(request("https://example.com/stream-b"))

        assertNull(session.attemptRetry(staleAttemptId))
    }

    @Test
    fun `attemptRetry returns null for an attempt that switched away and back to the same url`() {
        val staleAttemptId = session.onPlaybackStarted(request("https://example.com/stream-a"))
        session.onPlaybackStarted(request("https://example.com/stream-b"))
        // Switches back to the same URL as the stale attempt - a stream-url equality guard alone
        // would wrongly treat this as still current; the attempt id must not.
        session.onPlaybackStarted(request("https://example.com/stream-a"))

        assertNull(session.attemptRetry(staleAttemptId))
    }

    @Test
    fun `onStopped invalidates any in-flight retry but keeps the last request`() {
        val played = request()
        val attemptId = session.onPlaybackStarted(played)
        session.onPlaybackError(playbackException(2000))
        assertTrue(session.isPendingRetry)

        session.onStopped()

        assertFalse(session.isPendingRetry)
        assertNull(session.attemptRetry(attemptId))
        assertNull(session.activeStreamUrlOrNull())
        // Still names the last-played station (widget, getCurrentStationName).
        assertEquals(played, session.currentRequest)
    }

    @Test
    fun `onRecorderError marks the connection error and a pending retry`() {
        session.onPlaybackStarted(request())

        session.onRecorderError()

        assertEquals(now, session.connectionErrorAt)
        assertTrue(session.isPendingRetry)
        assertFalse(session.isConnectionBroken)
    }

    @Test
    fun `onNetworkAvailable retries immediately when a retry is pending`() {
        val attemptId = session.onPlaybackStarted(request())
        session.onPlaybackError(playbackException(2000)) // sets isPendingRetry

        val decision = session.onNetworkAvailable(isPlayerIdle = false)

        assertEquals(RetryDecision.RetryNow(attemptId), decision)
    }

    @Test
    fun `onNetworkAvailable retries after a recorder error`() {
        session.onPlaybackStarted(request())
        session.onRecorderError()

        assertTrue(session.onNetworkAvailable(isPlayerIdle = false) is RetryDecision.RetryNow)
    }

    @Test
    fun `onNetworkAvailable retries when the player is idle even without a pending flag`() {
        session.onPlaybackStarted(request())
        assertTrue(session.onNetworkAvailable(isPlayerIdle = true) is RetryDecision.RetryNow)
    }

    @Test
    fun `onNetworkAvailable is a no-op when nothing is pending and the player isn't idle`() {
        session.onPlaybackStarted(request())
        assertEquals(RetryDecision.NoAction, session.onNetworkAvailable(isPlayerIdle = false))
    }

    @Test
    fun `onNetworkAvailable is a no-op before anything has ever played or after a stop`() {
        assertEquals(RetryDecision.NoAction, session.onNetworkAvailable(isPlayerIdle = true))

        session.onPlaybackStarted(request())
        session.onStopped()

        assertEquals(RetryDecision.NoAction, session.onNetworkAvailable(isPlayerIdle = true))
    }

    @Test
    fun `onNetworkAvailable gives up once the retry budget is exhausted`() {
        session.onPlaybackStarted(request())
        exhaustRetries()
        assertEquals(RetryDecision.GiveUp, session.onNetworkAvailable(isPlayerIdle = true))
        assertTrue(session.isConnectionBroken)
    }
}
