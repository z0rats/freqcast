package com.freqcast.ui.playback

import androidx.media3.common.PlaybackException
import com.freqcast.ui.PlaybackRequest

/** Outcome of a [PlaybackSession] decision; the caller acts on it without touching the session's fields directly. */
sealed class RetryDecision {
    data class RetryNow(
        val attemptId: Long,
    ) : RetryDecision()

    data class RetryAfter(
        val delayMs: Long,
        val attemptId: Long,
    ) : RetryDecision()

    /** Retries exhausted or a non-retryable error: the session has already marked itself broken; the caller stops playback. */
    object GiveUp : RetryDecision()

    object NoAction : RetryDecision()
}

/**
 * All of [com.freqcast.ui.RadioPlaybackService]'s per-stream state and the rules that change it:
 * which station is playing, the reconnection state machine (retry count, capped exponential
 * backoff, a monotonic attempt id that invalidates a scheduled retry once a newer
 * [onPlaybackStarted] - a fresh play, or a station switch - has superseded it), and the two
 * connection-error signals the UI reads ([connectionErrorAt], [isConnectionBroken]). The service
 * reports events in and executes the decisions that come back; it holds none of this state itself.
 * Pure: no Android or ExoPlayer dependency beyond reading a [PlaybackException]'s code, so every rule
 * here is testable as plain Kotlin, without booting the service.
 */
class PlaybackSession(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * The station currently playing, or the last one played. Deliberately survives [onStopped]:
     * the service's `getCurrentStationName()` and the widget both keep naming the last-played
     * station after an explicit stop.
     */
    var currentRequest: PlaybackRequest? = null
        private set

    /**
     * Timestamp of the last connection failure (recorder I/O error, or retries exhausted), or null
     * if none has happened yet. Never reset back to null - consumers react to the value
     * *changing*, since a new failure simply overwrites it (drives a one-shot Toast).
     */
    var connectionErrorAt: Long? = null
        private set

    /**
     * Whether the current stream is in a give-up state. Unlike [connectionErrorAt], flips back to
     * false on the next [onPlaybackStarted], or the mini player would show ERROR forever after the
     * first failure of the session.
     */
    var isConnectionBroken: Boolean = false
        private set

    /** True while a retry is pending after a network error (drives the "reconnecting" notification text). */
    var isPendingRetry: Boolean = false
        private set

    /** Whether a stream is active for retry purposes - false before the first play and after [onStopped]. */
    private var isActive = false
    private var currentAttemptId = 0L
    private var retryCount = 0

    /** The active stream's URL, for display use (e.g. the widget) - null once stopped. */
    fun activeStreamUrlOrNull(): String? = currentRequest?.streamUrl?.takeIf { isActive }

    /** Call when a stream starts loading (a fresh play, or a retry of the same stream). Returns the new attempt id. */
    fun onPlaybackStarted(
        request: PlaybackRequest,
        isRetry: Boolean = false,
    ): Long {
        currentAttemptId++
        currentRequest = request
        isActive = true
        isPendingRetry = false
        isConnectionBroken = false
        if (!isRetry) retryCount = 0
        return currentAttemptId
    }

    /** Call when the stream loads successfully, to give a future failure a fresh retry budget. */
    fun onPlaybackSucceeded() {
        retryCount = 0
    }

    /** Decides how to react to a player error. */
    fun onPlaybackError(error: PlaybackException): RetryDecision {
        if (!isRetryableNetworkError(error) || retryCount >= MAX_RETRY_COUNT) return giveUp()
        retryCount++
        isPendingRetry = true
        return RetryDecision.RetryAfter(retryDelayMs(retryCount), currentAttemptId)
    }

    /**
     * A recorder-level I/O failure with no [PlaybackException] to classify: marks the connection
     * error and leaves recovery to the network-restored callback ([onNetworkAvailable]).
     */
    fun onRecorderError() {
        connectionErrorAt = clock()
        isPendingRetry = true
    }

    /** Decides how to react to the network coming back, given whether the player is currently idle. */
    fun onNetworkAvailable(isPlayerIdle: Boolean): RetryDecision {
        if (!isActive) return RetryDecision.NoAction
        if (!isPendingRetry && !isPlayerIdle) return RetryDecision.NoAction
        if (retryCount >= MAX_RETRY_COUNT) return giveUp()
        retryCount++
        return RetryDecision.RetryNow(currentAttemptId)
    }

    /** Returns the request to replay for [attemptId], or null if a newer attempt (or a stop) has since superseded it. */
    fun attemptRetry(attemptId: Long): PlaybackRequest? {
        if (attemptId != currentAttemptId || !isActive) return null
        return currentRequest
    }

    /**
     * Manual stop, a give-up, or service teardown: invalidates any in-flight retry and clears
     * retry state. [currentRequest] and the error signals survive - see their docs.
     */
    fun onStopped() {
        currentAttemptId++
        isActive = false
        retryCount = 0
        isPendingRetry = false
    }

    private fun giveUp(): RetryDecision {
        connectionErrorAt = clock()
        isConnectionBroken = true
        return RetryDecision.GiveUp
    }

    companion object {
        private const val MAX_RETRY_COUNT = 5
        private const val BASE_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L

        /** Exponential backoff (2s, 4s, 8s, 16s, capped at 30s) for the given 1-based retry attempt. */
        internal fun retryDelayMs(attempt: Int): Long {
            val delay = BASE_RETRY_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 4))
            return delay.coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        /** All IO/network error codes in media3 (2000–2010): timeout, connection failed, reset, unspecified, etc. */
        internal fun isRetryableNetworkError(error: PlaybackException): Boolean {
            val code = error.errorCode
            return code in 2000..2010
        }
    }
}
