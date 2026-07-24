package com.freqcast.ui.playback

import android.content.Context

/**
 * Persists app-wide user preferences (currently just the metered-connection playback warning),
 * mirroring [PlaybackStateStore]'s SharedPreferences-backed shape.
 */
class SettingsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var warnOnMeteredConnection: Boolean
        get() = prefs.getBoolean(KEY_WARN_ON_METERED, false)
        set(value) = prefs.edit().putBoolean(KEY_WARN_ON_METERED, value).apply()

    /** Timeshift (rewind) buffer size in MB. See [TimeshiftBufferSize] for the selectable presets. */
    var timeshiftBufferSizeMb: Int
        get() = prefs.getInt(KEY_TIMESHIFT_BUFFER_SIZE_MB, TimeshiftBufferSize.DEFAULT_MB)
        set(value) = prefs.edit().putInt(KEY_TIMESHIFT_BUFFER_SIZE_MB, value).apply()

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_WARN_ON_METERED = "warn_on_metered_connection"
        private const val KEY_TIMESHIFT_BUFFER_SIZE_MB = "timeshift_buffer_size_mb"
    }
}

/** Selectable presets for [SettingsStore.timeshiftBufferSizeMb], with their approximate rewind window at 128 kbps. */
enum class TimeshiftBufferSize(
    val mb: Int,
    val approxMinutes: Int,
) {
    SMALL(15, 12),
    DEFAULT(30, 25),
    LARGE(60, 50),
    EXTRA_LARGE(120, 100),
    ;

    companion object {
        const val DEFAULT_MB = 30
    }
}
