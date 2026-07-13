package com.urlradiodroid.ui

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

        val result = AlarmScheduler.schedule(ApplicationProvider.getApplicationContext(), 7, 30)

        assertTrue(result)
    }

    @Config(sdk = [31])
    @Test
    fun `schedule returns false instead of crashing when exact-alarm permission is missing`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = AlarmScheduler.schedule(ApplicationProvider.getApplicationContext(), 7, 30)

        assertFalse(result)
    }

    @Config(sdk = [29])
    @Test
    fun `schedule succeeds on API levels below the exact-alarm permission model`() {
        val result = AlarmScheduler.schedule(ApplicationProvider.getApplicationContext(), 7, 30)

        assertTrue(result)
    }
}
