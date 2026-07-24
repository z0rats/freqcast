package com.freqcast.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.freqcast.data.AlarmRepository
import com.freqcast.data.WakeAlarm
import com.freqcast.ui.playback.AlarmStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * [AlarmRepository.create] resolves the real [com.freqcast.data.AppDatabase] singleton
 * (`BootReceiver`/`ActionCallback`s have no injection seam for a test DB — see AGENTS.md's Testing
 * section), which is cached for the process lifetime and so persists across `@Test` methods in this
 * class. [setup] defensively clears any alarms left over from a previous test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BootReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val receiver = BootReceiver()
    private val repository = AlarmRepository.create(context)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    @Before
    fun setup() {
        runBlocking {
            repository.getAllAlarms().forEach { repository.deleteAlarm(it.id) }
        }
    }

    @Test
    fun `onReceive reschedules every enabled alarm on boot completed`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        runBlocking {
            repository.insertAlarm(
                WakeAlarm(enabled = true, hour = 7, minute = 0, stationName = "A", streamUrl = "https://a"),
            )
            repository.insertAlarm(
                WakeAlarm(enabled = true, hour = 8, minute = 0, stationName = "B", streamUrl = "https://b"),
            )
            repository.insertAlarm(
                WakeAlarm(enabled = false, hour = 9, minute = 0, stationName = "C", streamUrl = "https://c"),
            )
        }

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `onReceive does nothing for an intent other than boot completed`() {
        runBlocking {
            repository.insertAlarm(
                WakeAlarm(enabled = true, hour = 7, minute = 0, stationName = "A", streamUrl = "https://a"),
            )
        }

        receiver.onReceive(context, Intent("some.other.action"))

        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `onReceive migrates and reschedules a still-pending legacy alarm`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AlarmStateStore(context).save(
            AlarmStateStore.Alarm(
                enabled = true,
                hour = 6,
                minute = 30,
                stationName = "Legacy FM",
                streamUrl = "https://stream.example.com/legacy",
            ),
        )

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        val migrated = runBlocking { repository.getAllAlarms() }
        assertEquals(1, migrated.size)
        assertEquals("Legacy FM", migrated[0].stationName)
    }
}
