package com.freqcast.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.freqcast.ui.RadioPlaybackService

enum class PlaybackStatus { PLAYING, PAUSED, STARTING, ERROR }

/**
 * Pure decision table for the mini/full player's status pill - the single place
 * [PlaybackStatus] is computed, so [com.freqcast.ui.MainScreen] and [com.freqcast.ui.PlaybackScreen]
 * can't drift into their own ad-hoc state machines. [isConnectionBroken] wins over
 * [isBuffering]/[isRetryPending] since a give-up is a terminal state for the current stream, not a
 * transient one.
 */
internal fun computePlaybackStatus(
    isPlaying: Boolean,
    isBuffering: Boolean,
    isRetryPending: Boolean,
    isConnectionBroken: Boolean,
): PlaybackStatus =
    when {
        isPlaying -> PlaybackStatus.PLAYING
        isConnectionBroken -> PlaybackStatus.ERROR
        isBuffering || isRetryPending -> PlaybackStatus.STARTING
        else -> PlaybackStatus.PAUSED
    }

/**
 * Display-facing subset of [RadioPlaybackService.playbackSnapshot], scoped to a specific stream
 * via [rememberPlaybackPresentation]'s `currentStreamUrl`. Superset of what [com.freqcast.ui.MainScreen]
 * and [com.freqcast.ui.NowPlayingContent] each need individually - every caller reads only the
 * fields relevant to it.
 */
data class PlaybackPresentation(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasTimeshift: Boolean = false,
    val isAtLive: Boolean = true,
    val trackTitle: String? = null,
    val sleepTimerEndAtMs: Long? = null,
    val bufferedDurationMs: Long = 0L,
    val offsetFromLiveMs: Long = 0L,
    val clipFormatAvailable: Boolean = false,
    val connectionErrorAt: Long? = null,
    val status: PlaybackStatus = PlaybackStatus.PAUSED,
)

/**
 * Mirrors [playbackService]'s [RadioPlaybackService.playbackSnapshot] into a [PlaybackPresentation]
 * scoped to [currentStreamUrl] (null matches any stream, i.e. no scoping).
 * [PlaybackPresentation.bufferedDurationMs]/[PlaybackPresentation.offsetFromLiveMs] tick once a
 * second at the source - see [RadioPlaybackService]'s timeshift ticker - so this just collects,
 * it never polls the service itself.
 */
@Composable
fun rememberPlaybackPresentation(
    playbackService: RadioPlaybackService?,
    currentStreamUrl: String?,
): PlaybackPresentation {
    var presentation by remember { mutableStateOf(PlaybackPresentation()) }

    LaunchedEffect(playbackService, currentStreamUrl) {
        val svc = playbackService
        if (svc == null) {
            presentation = PlaybackPresentation()
            return@LaunchedEffect
        }
        svc.playbackSnapshot.collect { snapshot ->
            val isCurrent = currentStreamUrl == null || snapshot.currentMediaId == currentStreamUrl
            val isPlaying = snapshot.isPlaying && isCurrent
            val isBuffering = snapshot.isBuffering && isCurrent
            presentation =
                presentation.copy(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    hasTimeshift = snapshot.hasTimeshift && isCurrent,
                    isAtLive = snapshot.isAtLive,
                    trackTitle = if (isCurrent) snapshot.trackTitle else null,
                    sleepTimerEndAtMs = snapshot.sleepTimerEndAtMs,
                    // Not scoped to isCurrent: a connection error is a service-level event, not
                    // continuous per-stream state, and filtering it could lose the error to the
                    // same currentPlayingStationId desync that affects isCurrent elsewhere.
                    connectionErrorAt = snapshot.connectionErrorAt,
                    // Not scoped to isCurrent either: the service only ever timeshifts one stream
                    // at a time, so there's nothing else these values could belong to.
                    bufferedDurationMs = snapshot.bufferedDurationMs,
                    offsetFromLiveMs = snapshot.offsetFromLiveMs,
                    clipFormatAvailable = snapshot.clipFormatAvailable,
                    status =
                        computePlaybackStatus(
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            isRetryPending = snapshot.isRetryPending,
                            isConnectionBroken = snapshot.isConnectionBroken,
                        ),
                )
        }
    }
    return presentation
}

/** Live `(currentMediaId, isPlaying)` pair from the service, unscoped by any notion of "current station". */
data class RawPlaybackState(
    val currentMediaId: String? = null,
    val isPlaying: Boolean = false,
)

/**
 * Mirrors [playbackService]'s [RadioPlaybackService.playbackSnapshot] straight through, with no
 * `isCurrent` scoping - unlike [rememberPlaybackPresentation], which already depends on knowing
 * which station is "current" (via `currentStreamUrl`) and so can't be used to *determine* that in
 * the first place without becoming circular. Used by [com.freqcast.ui.MainScreen] to detect
 * playback started elsewhere (Android Auto, widget, resume-after-restart) and sync its ViewModel.
 */
@Composable
fun rememberRawPlaybackState(playbackService: RadioPlaybackService?): RawPlaybackState {
    var state by remember { mutableStateOf(RawPlaybackState()) }
    LaunchedEffect(playbackService) {
        val svc = playbackService
        if (svc == null) {
            state = RawPlaybackState()
            return@LaunchedEffect
        }
        svc.playbackSnapshot.collect { snapshot ->
            state = RawPlaybackState(currentMediaId = snapshot.currentMediaId, isPlaying = snapshot.isPlaying)
        }
    }
    return state
}
