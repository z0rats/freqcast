package com.urlradiodroid.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import com.urlradiodroid.ui.RadioPlaybackService
import com.urlradiodroid.ui.playback.WidgetStateStore
import com.urlradiodroid.util.StationNavigator

/** Tap on the widget's play/pause button: stops if currently playing, otherwise resumes the last station. */
class TogglePlaybackAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val saved = WidgetStateStore(context).restore() ?: return
        if (saved.isPlaying) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RadioPlaybackService::class.java).setAction(RadioPlaybackService.ACTION_STOP),
            )
        } else {
            startStation(context, saved.stationName, saved.streamUrl)
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
    startStation(context, target.name, target.streamUrl)
}

private fun startStation(
    context: Context,
    stationName: String?,
    streamUrl: String,
) {
    val intent =
        Intent(context, RadioPlaybackService::class.java).apply {
            putExtra(RadioPlaybackService.EXTRA_STATION_NAME, stationName)
            putExtra(RadioPlaybackService.EXTRA_STREAM_URL, streamUrl)
        }
    ContextCompat.startForegroundService(context, intent)
}
