package com.freqcast.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.playback.PlaybackCommands
import com.freqcast.ui.playback.WidgetStateStore
import com.freqcast.util.StationNavigator

/** Tap on the widget's play/pause button: stops if currently playing, otherwise resumes the last station. */
class TogglePlaybackAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val saved = WidgetStateStore(context).restore() ?: return
        if (saved.isPlaying) {
            PlaybackCommands.stop(context)
        } else {
            PlaybackCommands.start(context, saved.stationName, saved.streamUrl)
        }
    }
}

/** Tap on the widget's next button: starts the station after the current one (wraps around). */
class NextStationAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) = skipTo(context, StationNavigator::next)
}

/** Tap on the widget's previous button: starts the station before the current one (wraps around). */
class PreviousStationAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) = skipTo(context, StationNavigator::previous)
}

private suspend fun skipTo(
    context: Context,
    pick: (List<RadioStation>, String?) -> RadioStation?,
) {
    val stations = RadioStationRepository.create(context).getAllStations()
    val currentStreamUrl = WidgetStateStore(context).restore()?.streamUrl
    val target = pick(stations, currentStreamUrl) ?: return
    PlaybackCommands.start(context, target)
}
