package com.freqcast.ui.playback.controller

import com.freqcast.data.RadioStation
import com.freqcast.ui.PlaybackSnapshot
import com.freqcast.ui.playback.ClipFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * In-memory [PlaybackController] test double - the second adapter that makes this seam real rather
 * than hypothetical (see [ServiceBackedPlaybackController] for the production one). Exercises the
 * same [decideToggleAction] the real adapter uses, so tests written against this fake also cover
 * the toggle decision's behaviour, not just wiring.
 */
class FakePlaybackController(
    initialSnapshot: PlaybackSnapshot = PlaybackSnapshot(),
    var isNetworkAvailable: Boolean = true,
) : PlaybackController {
    private val snapshotFlow = MutableStateFlow(initialSnapshot)
    override val playbackSnapshot: StateFlow<PlaybackSnapshot> = snapshotFlow

    var fakeStationName: String? = null
    var clipFormat: ClipFormat? = null
    var exportClipResult: Boolean = true

    val toggledStations = mutableListOf<RadioStation>()
    var stopPlaybackCallCount = 0
        private set
    val seekBackwardCalls = mutableListOf<Long>()
    val seekToOffsetCalls = mutableListOf<Long>()
    var seekToLiveCallCount = 0
        private set
    var sleepTimerMinutes: Int? = null

    override fun getCurrentStationName(): String? = fakeStationName

    override suspend fun toggle(station: RadioStation): ToggleResult {
        val snapshot = snapshotFlow.value
        return when (
            decideToggleAction(
                stationStreamUrl = station.streamUrl,
                currentMediaId = snapshot.currentMediaId,
                isPlaying = snapshot.isPlaying,
                isNetworkAvailable = isNetworkAvailable,
            )
        ) {
            ToggleAction.STOP -> {
                stopPlayback()
                ToggleResult.STOPPED
            }

            ToggleAction.REJECT_NO_NETWORK -> {
                ToggleResult.NETWORK_UNAVAILABLE
            }

            ToggleAction.START -> {
                toggledStations += station
                fakeStationName = station.name
                snapshotFlow.value = snapshot.copy(isPlaying = true, currentMediaId = station.streamUrl)
                ToggleResult.STARTED
            }
        }
    }

    override fun stopPlayback() {
        stopPlaybackCallCount++
        snapshotFlow.value = snapshotFlow.value.copy(isPlaying = false)
    }

    override fun seekBackward(ms: Long) {
        seekBackwardCalls += ms
    }

    override fun seekToLive() {
        seekToLiveCallCount++
    }

    override fun seekToOffsetFromLive(offsetMs: Long) {
        seekToOffsetCalls += offsetMs
    }

    override fun setSleepTimer(minutes: Int) {
        sleepTimerMinutes = minutes
    }

    override fun cancelSleepTimer() {
        sleepTimerMinutes = null
    }

    override fun currentClipFormat(): ClipFormat? = clipFormat

    override fun exportClip(
        durationMs: Long,
        destination: File,
        onResult: (Boolean) -> Unit,
    ) = onResult(exportClipResult)
}
