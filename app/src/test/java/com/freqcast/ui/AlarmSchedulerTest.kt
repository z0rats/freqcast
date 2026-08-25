package com.freqcast.ui

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Calendar

/**
 * Covers [AlarmScheduler.nextTriggerMillis]'s pure "today or tomorrow" decision, plus
 * [AlarmScheduler.schedule]'s exact-alarm permission gate (added after `setAlarmClock()` was
 * observed throwing `SecurityException` on some devices despite the documented alarm-clock
 * exemption — see that function's doc comment).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AlarmSchedulerTest {
    private fun calendarAt(
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): Calendar =
        Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun `schedules later today when the target time hasn't passed yet`() {
        val now = calendarAt(6, 0)
        val expected = calendarAt(7, 30)

        val result = AlarmScheduler.nextTriggerMillis(7, 30, now.timeInMillis)

        assertEquals(expected.timeInMillis, result)
    }

    @Test
    fun `schedules tomorrow when the target time already passed today`() {
        val now = calendarAt(8, 0)
        val expected =
            calendarAt(7, 30).apply { add(Calendar.DAY_OF_YEAR, 1) }

        val result = AlarmScheduler.nextTriggerMillis(7, 30, now.timeInMillis)

        assertEquals(expected.timeInMillis, result)
    }

    @Test
    fun `schedules tomorrow when now is exactly the target time`() {
        val now = calendarAt(7, 30, second = 0)
        val expected = calendarAt(7, 30).apply { add(Calendar.DAY_OF_YEAR, 1) }

        val result = AlarmScheduler.nextTriggerMillis(7, 30, now.timeInMillis)

        assertEquals(expected.timeInMillis, result)
    }

    @Config(sdk = [31])
    @Test
    fun `schedule succeeds when exact-alarm permission is granted`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val result = AlarmScheduler.schedule(ApplicationProvider.getApplicationContext(), 1L, 7, 30)

        assertTrue(result)
    }

    @Config(sdk = [31])
    @Test
    fun `schedule returns false instead of crashing when exact-alarm permission is missing`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = AlarmScheduler.schedule(ApplicationProvider.getApplicationContext(), 1L, 7, 30)

        assertFalse(result)
    }

    @Config(sdk = [29])
    @Test
    fun `schedule succeeds on API levels below the exact-alarm permission model`() {
        val result = AlarmScheduler.schedule(ApplicationProvider.getApplicationContext(), 1L, 7, 30)

        assertTrue(result)
    }

    @Config(sdk = [31])
    @Test
    fun `schedule and cancel for one alarm id don't affect another`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(alarmManager)

        AlarmScheduler.schedule(context, 1L, 7, 30)
        AlarmScheduler.schedule(context, 2L, 8, 0)
        assertEquals(2, shadow.scheduledAlarms.size)

        AlarmScheduler.cancel(context, 1L)
        assertEquals(1, shadow.scheduledAlarms.size)
    }

    @Test
    fun `cancelLegacy cancels the pre-multi-alarm alarm's PendingIntent if still registered`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(alarmManager)
        // Mirrors exactly what the pre-multi-alarm code used to register (component + request code
        // 1001, per AlarmScheduler.LEGACY_REQUEST_CODE's doc) - PendingIntent cancellation matches
        // by that shape, not by object identity, so cancelLegacy() must build the identical one.
        val legacyIntent =
            android.app.PendingIntent.getBroadcast(
                context,
                1001,
                android.content.Intent(context, AlarmReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 60_000, legacyIntent)
        assertEquals(1, shadow.scheduledAlarms.size)

        AlarmScheduler.cancelLegacy(context)

        assertEquals(0, shadow.scheduledAlarms.size)
    }

    @Test
    fun `cancelLegacy is a no-op when no legacy alarm was ever registered`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(alarmManager)

        AlarmScheduler.cancelLegacy(context)

        assertEquals(0, shadow.scheduledAlarms.size)
    }
}
