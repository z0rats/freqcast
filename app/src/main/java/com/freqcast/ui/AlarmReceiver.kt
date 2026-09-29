package com.freqcast.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.freqcast.data.AlarmRepository
import com.freqcast.ui.playback.AlarmStateStore
import com.freqcast.ui.playback.PlaybackCommands
import kotlinx.coroutines.runBlocking

/**
 * Fired by [AlarmScheduler] at a wake-up alarm's scheduled time: starts playback, then reschedules
 * that same alarm for tomorrow. [EXTRA_ALARM_ID] identifies which `WakeAlarm` row fired; a
 * missing/-1 value means this is the pre-multi-alarm single alarm's [android.app.PendingIntent],
 * still registered with [android.app.AlarmManager] from before an app update ran the one-time
 * migration (an app update doesn't clear scheduled alarms, only a reboot does) — falls back to
 * importing (and then using) the legacy SharedPreferences-backed alarm via
 * [AlarmRepository.migrateLegacyAlarmIfNeeded] instead of silently dropping it.
 *
 * Uses [runBlocking] rather than `goAsync()` — the Room lookup this does is small and bounded,
 * well within a BroadcastReceiver's execution budget, and keeps this directly testable via a plain
 * `onReceive()` call (no system-provided `PendingResult` to synchronize against).
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        runBlocking {
            val repository = AlarmRepository.create(context)
            val alarm =
                if (alarmId != -1L) {
                    repository.getAlarmById(alarmId)
                } else {
                    repository.migrateLegacyAlarmIfNeeded(AlarmStateStore(context))
                }
            val streamUrl = alarm?.streamUrl
            if (alarm == null || !alarm.enabled || streamUrl == null) return@runBlocking

            PlaybackCommands.start(context, alarm.stationName, streamUrl)

            AlarmScheduler.schedule(context, alarm.id, alarm.hour, alarm.minute)
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}
