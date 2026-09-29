package com.freqcast.ui.playback.controller

import android.content.Context
import com.freqcast.data.RadioStation
import com.freqcast.ui.PlaybackSnapshot
import com.freqcast.ui.RadioPlaybackService
import com.freqcast.ui.playback.ClipFormat
import com.freqcast.ui.playback.PlaybackCommands
import com.freqcast.util.isNetworkAvailable
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Production [PlaybackController] adapter, wrapping a bound [RadioPlaybackService]. */
class ServiceBackedPlaybackController(
    private val appContext: Context,
    private val service: RadioPlaybackService,
) : PlaybackController {
    override val playbackSnapshot: StateFlow<PlaybackSnapshot> = service.playbackSnapshot

    override fun getCurrentStationName(): String? = service.getCurrentStationName()

    override suspend fun toggle(station: RadioStation): ToggleResult {
        val snapshot = service.playbackSnapshot.value
        val action =
            decideToggleAction(
                stationStreamUrl = station.streamUrl,
                currentMediaId = snapshot.currentMediaId,
                isPlaying = snapshot.isPlaying,
                isNetworkAvailable = isNetworkAvailable(appContext),
            )
        return when (action) {
            ToggleAction.STOP -> {
                service.stopPlayback()
                ToggleResult.STOPPED
            }

            ToggleAction.REJECT_NO_NETWORK -> {
                ToggleResult.NETWORK_UNAVAILABLE
            }

            ToggleAction.START -> {
                PlaybackCommands.start(appContext, station)
                ToggleResult.STARTED
            }
        }
    }

    override fun stopPlayback() = service.stopPlayback()

    override fun seekBackward(ms: Long) = service.seekBackward(ms)

    override fun seekToLive() = service.seekToLive()

    override fun seekToOffsetFromLive(offsetMs: Long) = service.seekToOffsetFromLive(offsetMs)

    override fun setSleepTimer(minutes: Int) = service.setSleepTimer(minutes)

    override fun cancelSleepTimer() = service.cancelSleepTimer()

    override fun currentClipFormat(): ClipFormat? = service.currentClipFormat()

    override fun exportClip(
        durationMs: Long,
        destination: File,
        onResult: (Boolean) -> Unit,
    ) = service.exportClip(durationMs, destination, onResult)
}
