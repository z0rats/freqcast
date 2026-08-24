package com.freqcast.ui.playback.controller

import com.freqcast.data.RadioStation
import com.freqcast.ui.PlaybackSnapshot
import com.freqcast.ui.playback.ClipFormat
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Narrow command+state seam for playback UI: what MainScreen/PlaybackScreen/NowPlayingBottomBar
 * actually need from a bound RadioPlaybackService, instead of the service's full public API
 * (getPlayer(), isBuffering(), hasTimeshift(), the MediaLibrarySession callbacks, ...).
 * [ServiceBackedPlaybackController] is the production adapter; a fake in the test source set is
 * the other - the seam is real, not hypothetical, because both are actually used: production binds
 * to the service, tests exercise MainScreen/PlaybackScreen's toggle/error-reaction wiring without it.
 */
interface PlaybackController {
    val playbackSnapshot: StateFlow<PlaybackSnapshot>

    fun getCurrentStationName(): String?

    /** Starts [station] if nothing (or a different station) is playing, stops if it's already playing. */
    suspend fun toggle(station: RadioStation): ToggleResult

    fun stopPlayback()

    fun seekBackward(ms: Long)

    fun seekToLive()

    fun seekToOffsetFromLive(offsetMs: Long)

    fun setSleepTimer(minutes: Int)

    fun cancelSleepTimer()

    fun currentClipFormat(): ClipFormat?

    fun exportClip(
        durationMs: Long,
        destination: File,
        onResult: (Boolean) -> Unit,
    )
}

enum class ToggleResult { STARTED, STOPPED, NETWORK_UNAVAILABLE }

internal enum class ToggleAction { START, STOP, REJECT_NO_NETWORK }

/**
 * Pure start/stop decision behind [PlaybackController.toggle], factored out so it's unit-testable
 * without Robolectric - same shape as [com.freqcast.ui.playback.ConnectionRetryPolicy]. Compares by
 * stream URL (a station's [RadioStation.streamUrl] is always its media id - see RadioBrowseTree),
 * checking "is this station already playing" *before* the network check, so stopping the current
 * stream never needs a network. One decision now backs every toggle call site; previously
 * MainActivity.playStation compared by station id via the ViewModel while PlaybackActivity.togglePlayback
 * compared by "is anything at all playing", which could stop the wrong stream if a different one
 * was playing when the screen opened.
 */
internal fun decideToggleAction(
    stationStreamUrl: String,
    currentMediaId: String?,
    isPlaying: Boolean,
    isNetworkAvailable: Boolean,
): ToggleAction {
    val isCurrentlyPlayingThis = isPlaying && currentMediaId == stationStreamUrl
    return when {
        isCurrentlyPlayingThis -> ToggleAction.STOP
        !isNetworkAvailable -> ToggleAction.REJECT_NO_NETWORK
        else -> ToggleAction.START
    }
}
