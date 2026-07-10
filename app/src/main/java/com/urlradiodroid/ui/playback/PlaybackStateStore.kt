package com.urlradiodroid.ui.playback

import android.content.Context

/**
 * Persists which station was last started so playback can resume automatically if the system
 * kills and restarts the service (Android redelivers [android.app.Service.onStartCommand] with a
 * null intent in that case). Cleared on explicit user stop so we don't resurrect a station the
 * user intentionally turned off.
 */
class PlaybackStateStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        stationName: String?,
        streamUrl: String,
    ) {
        prefs
            .edit()
            .putString(KEY_STATION_NAME, stationName)
            .putString(KEY_STREAM_URL, streamUrl)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun restore(): Saved? {
        val url = prefs.getString(KEY_STREAM_URL, null) ?: return null
        return Saved(stationName = prefs.getString(KEY_STATION_NAME, null), streamUrl = url)
    }

    data class Saved(
        val stationName: String?,
        val streamUrl: String,
    )

    companion object {
        private const val PREFS_NAME = "playback_state"
        private const val KEY_STATION_NAME = "station_name"
        private const val KEY_STREAM_URL = "stream_url"
    }
}
