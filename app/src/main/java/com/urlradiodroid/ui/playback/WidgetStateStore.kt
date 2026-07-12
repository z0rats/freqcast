package com.urlradiodroid.ui.playback

import android.content.Context

/**
 * Persists the state the home screen widget (Glance, see `widget/RadioWidget`) needs to render:
 * which station is showing and whether it's currently playing. Separate from [PlaybackStateStore]
 * because that store's contract is "last STARTED station, cleared on explicit stop" (so it can
 * resume playback after a process death) — the widget instead wants to keep showing the last
 * station with a play affordance even after the user explicitly stops, rather than going blank.
 */
class WidgetStateStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        stationName: String?,
        streamUrl: String?,
        isPlaying: Boolean,
    ) {
        prefs
            .edit()
            .putString(KEY_STATION_NAME, stationName)
            .putString(KEY_STREAM_URL, streamUrl)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .apply()
    }

    fun restore(): State? {
        val url = prefs.getString(KEY_STREAM_URL, null) ?: return null
        return State(
            stationName = prefs.getString(KEY_STATION_NAME, null),
            streamUrl = url,
            isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false),
        )
    }

    data class State(
        val stationName: String?,
        val streamUrl: String,
        val isPlaying: Boolean,
    )

    companion object {
        private const val PREFS_NAME = "widget_state"
        private const val KEY_STATION_NAME = "station_name"
        private const val KEY_STREAM_URL = "stream_url"
        private const val KEY_IS_PLAYING = "is_playing"
    }
}
