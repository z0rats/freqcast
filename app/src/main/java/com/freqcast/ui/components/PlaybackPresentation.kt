package com.freqcast.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.freqcast.ui.RadioPlaybackService
import kotlinx.coroutines.delay

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
)

/**
 * Mirrors [playbackService]'s [RadioPlaybackService.playbackSnapshot] into a [PlaybackPresentation]
 * scoped to [currentStreamUrl] (null matches any stream, i.e. no scoping). Also ticks
 * [PlaybackPresentation.bufferedDurationMs]/[PlaybackPresentation.offsetFromLiveMs] once a second
 * while timeshift is active, since those grow continuously rather than changing on discrete events.
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
            presentation =
                presentation.copy(
                    isPlaying = snapshot.isPlaying && isCurrent,
                    isBuffering = snapshot.isBuffering && isCurrent,
                    hasTimeshift = snapshot.hasTimeshift && isCurrent,
                    isAtLive = snapshot.isAtLive,
                    trackTitle = if (isCurrent) snapshot.trackTitle else null,
                    sleepTimerEndAtMs = snapshot.sleepTimerEndAtMs,
                )
        }
    }

    LaunchedEffect(playbackService, presentation.hasTimeshift) {
        val svc = playbackService
        if (svc == null || !presentation.hasTimeshift) {
            presentation =
                presentation.copy(bufferedDurationMs = 0L, offsetFromLiveMs = 0L, clipFormatAvailable = false)
            return@LaunchedEffect
        }
        while (true) {
            presentation =
                presentation.copy(
                    bufferedDurationMs = svc.bufferedDurationMs(),
                    offsetFromLiveMs = svc.offsetFromLiveMs(),
                    clipFormatAvailable = svc.currentClipFormat() != null,
                )
            delay(1_000)
        }
    }
    return presentation
}
