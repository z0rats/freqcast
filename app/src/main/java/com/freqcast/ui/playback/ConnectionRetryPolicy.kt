package com.freqcast.ui.playback

import androidx.media3.common.PlaybackException

/** Outcome of a [ConnectionRetryPolicy] decision; the caller acts on it without touching the policy's fields directly. */
sealed class RetryDecision {
    data class RetryNow(
        val attemptId: Long,
    ) : RetryDecision()

    data class RetryAfter(
        val delayMs: Long,
        val attemptId: Long,
    ) : RetryDecision()

    object GiveUp : RetryDecision()

    object NoAction : RetryDecision()
}

/** The stream to replay for a still-current attempt; see [ConnectionRetryPolicy.attemptRetry]. */
data class RetryTarget(
    val streamUrl: String,
    val knownHls: Boolean?,
)

/**
 * Owns [RadioPlaybackService]'s reconnection state machine: retry count, capped exponential
 * backoff, and a monotonic attempt id that invalidates a scheduled retry once a newer
 * [onPlaybackStarted] call (a fresh play, or a station switch) has superseded it — this is what
 * stops a delayed retry from firing against a stream the user has since switched away from. Pure:
 * no Android or ExoPlayer dependency, so it's testable as plain Kotlin.
 */
class ConnectionRetryPolicy {
    private var currentAttemptId = 0L
    private var currentUrl: String? = null
    private var currentKnownHls: Boolean? = null
    private var retryCount = 0
    private var pendingRetry = false

    /** Call when a stream starts loading (a fresh play, or a retry of the same stream). Returns the new attempt id. */
    fun onPlaybackStarted(
        url: String,
        knownHls: Boolean?,
        isRetry: Boolean = false,
    ): Long {
        currentAttemptId++
        currentUrl = url
        currentKnownHls = knownHls
        pendingRetry = false
        if (!isRetry) retryCount = 0
        return currentAttemptId
    }

    /** Call when the stream loads successfully, to give a future failure a fresh retry budget. */
    fun onPlaybackSucceeded() {
        retryCount = 0
    }

    /** True while a retry is pending after a network error (drives the "reconnecting" notification text). */
    fun isPendingRetry(): Boolean = pendingRetry

    /** Marks a retry as pending without an [onPlaybackError] classification — e.g. a recorder-level I/O failure with no [PlaybackException] to inspect. Left for the network-restored callback to act on. */
    fun markPendingRetry() {
        pendingRetry = true
    }

    /** The stream currently tracked for retry purposes, for display use (e.g. the widget) — not a retry decision. */
    fun currentStreamUrlOrNull(): String? = currentUrl

    /** Decides how to react to a player error. */
    fun onPlaybackError(error: PlaybackException): RetryDecision {
        if (!isRetryableNetworkError(error)) return RetryDecision.GiveUp
        if (retryCount >= MAX_RETRY_COUNT) return RetryDecision.GiveUp
        retryCount++
        pendingRetry = true
        return RetryDecision.RetryAfter(retryDelayMs(retryCount), currentAttemptId)
    }

    /** Decides how to react to the network coming back, given whether the player is currently idle. */
    fun onNetworkAvailable(isPlayerIdle: Boolean): RetryDecision {
        if (currentUrl == null) return RetryDecision.NoAction
        val shouldRetry = pendingRetry || isPlayerIdle
        if (!shouldRetry) return RetryDecision.NoAction
        if (retryCount >= MAX_RETRY_COUNT) return RetryDecision.GiveUp
        retryCount++
        return RetryDecision.RetryNow(currentAttemptId)
    }

    /** Returns the stream to replay for [attemptId], or null if a newer attempt has since superseded it. */
    fun attemptRetry(attemptId: Long): RetryTarget? {
        if (attemptId != currentAttemptId) return null
        val url = currentUrl ?: return null
        return RetryTarget(url, currentKnownHls)
    }

    /** Clears all state and invalidates any in-flight retry. Call on manual stop or service teardown. */
    fun reset() {
        currentAttemptId++
        currentUrl = null
        currentKnownHls = null
        retryCount = 0
        pendingRetry = false
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
