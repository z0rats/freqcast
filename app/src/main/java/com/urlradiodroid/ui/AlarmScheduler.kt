package com.urlradiodroid.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Schedules/cancels the wake-up alarm via [AlarmManager.setAlarmClock]. Unlike
 * `setExactAndAllowWhileIdle`, apps that show a user-visible alarm (this one shows the status bar
 * alarm-clock icon, via [AlarmManager.AlarmClockInfo]) are *normally* exempt from the
 * `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` permission dance — but that exemption isn't guaranteed
 * on every OS build/device (observed in practice: `setAlarmClock()` itself throwing
 * `SecurityException` demanding one of those permissions). [schedule] therefore checks
 * [AlarmManager.canScheduleExactAlarms] first and also catches the exception as a last-resort
 * safety net, since [BootReceiver]/[AlarmReceiver] call this with no UI to recover from a crash —
 * it must never throw.
 */
object AlarmScheduler {
    private const val REQUEST_CODE = 1001

    /** Returns true if the alarm was actually scheduled, false if exact-alarm permission is missing. */
    fun schedule(
        context: Context,
        hour: Int,
        minute: Int,
    ): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return false
        }
        val triggerAtMillis = nextTriggerMillis(hour, minute, System.currentTimeMillis())
        val showIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
                operationIntent(context),
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(operationIntent(context))
    }

    private fun operationIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Next occurrence of [hour]:[minute] at/after [nowMillis] — today if that time hasn't passed yet, else tomorrow. */
    internal fun nextTriggerMillis(
        hour: Int,
        minute: Int,
        nowMillis: Long,
    ): Long {
        val calendar =
            Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        if (calendar.timeInMillis <= nowMillis) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }
}
