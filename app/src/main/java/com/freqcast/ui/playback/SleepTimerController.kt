package com.freqcast.ui.playback

import android.os.Handler

/**
 * Schedules "stop playback after N minutes", replacing any timer already running. Mirrors
 * [TimeshiftController]'s role: [com.freqcast.ui.RadioPlaybackService] delegates the
 * handler/runnable bookkeeping here instead of holding it directly.
 */
class SleepTimerController(
    private val mainHandler: Handler,
    private val onFire: () -> Unit,
) {
    private var runnable: Runnable? = null
    private var endAtMs: Long? = null

    /** Non-null while a timer is running, for [com.freqcast.ui.PlaybackSnapshot.sleepTimerEndAtMs]. */
    fun endAtMsOrNull(): Long? = endAtMs

    fun start(minutes: Int) {
        cancel()
        val durationMs = minutes * 60_000L
        val scheduled = Runnable { onFire() }
        runnable = scheduled
        endAtMs = System.currentTimeMillis() + durationMs
        mainHandler.postDelayed(scheduled, durationMs)
    }

    fun cancel() {
        runnable?.let { mainHandler.removeCallbacks(it) }
        runnable = null
        endAtMs = null
    }
}
