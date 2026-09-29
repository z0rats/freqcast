package com.freqcast.ui.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.freqcast.data.RadioStation
import com.freqcast.ui.RadioPlaybackService

/**
 * The one place that knows how a "play this station" / "stop" command is carried to
 * [RadioPlaybackService] as a started-service [Intent] - every caller that isn't holding a bound
 * service (Activities before binding, widget, alarm, AppFunctions,
 * [com.freqcast.ui.playback.controller.ServiceBackedPlaybackController]) goes through [start]/[stop],
 * and the service decodes with [parse]. The extras/action strings are private to this file, so the
 * encode and decode sides can't drift apart.
 */
object PlaybackCommands {
    /** What a start intent asked [RadioPlaybackService] to do; `null` from [parse] means "no command" (e.g. a START_STICKY restart). */
    sealed interface Command {
        data class Start(
            val stationName: String?,
            val streamUrl: String,
        ) : Command

        data object Stop : Command
    }

    fun start(
        context: Context,
        station: RadioStation,
    ) = start(context, station.name, station.streamUrl)

    /**
     * For callers that only hold a name/URL pair (widget state, alarms, `PlaybackActivity`'s intent
     * extras) - the service looks the full station up by [streamUrl] itself.
     */
    fun start(
        context: Context,
        stationName: String?,
        streamUrl: String,
    ) = ContextCompat.startForegroundService(context, startIntent(context, stationName, streamUrl))

    fun stop(context: Context) = ContextCompat.startForegroundService(context, stopIntent(context))

    internal fun startIntent(
        context: Context,
        stationName: String?,
        streamUrl: String,
    ): Intent =
        Intent(context, RadioPlaybackService::class.java)
            .putExtra(EXTRA_STATION_NAME, stationName)
            .putExtra(EXTRA_STREAM_URL, streamUrl)

    internal fun stopIntent(context: Context): Intent =
        Intent(context, RadioPlaybackService::class.java).setAction(ACTION_STOP)

    fun parse(intent: Intent?): Command? {
        if (intent == null) return null
        if (intent.action == ACTION_STOP) return Command.Stop
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return null
        return Command.Start(intent.getStringExtra(EXTRA_STATION_NAME), streamUrl)
    }

    private const val EXTRA_STATION_NAME = "station_name"
    private const val EXTRA_STREAM_URL = "stream_url"
    private const val ACTION_STOP = "com.freqcast.action.STOP"
}
