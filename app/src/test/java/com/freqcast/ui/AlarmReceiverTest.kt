package com.freqcast.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.freqcast.data.AlarmRepository
import com.freqcast.data.WakeAlarm
import com.freqcast.ui.playback.AlarmStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [AlarmRepository.create] resolves the real [com.freqcast.data.AppDatabase] singleton, which
 * is cached for the process lifetime and so persists across `@Test` methods in this class (see
 * AGENTS.md's Testing section) — [setup] defensively clears leftover alarms from a previous test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AlarmReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val receiver = AlarmReceiver()
    private val repository = AlarmRepository.create(context)

    @Before
    fun setup() {
        runBlocking {
            repository.getAllAlarms().forEach { repository.deleteAlarm(it.id) }
        }
    }

    @Test
    fun `onReceive starts playback of the alarm identified by EXTRA_ALARM_ID`() {
        val id =
            runBlocking {
                repository.insertAlarm(
                    WakeAlarm(
                        enabled = true,
                        hour = 7,
                        minute = 0,
                        stationName = "Morning FM",
                        streamUrl = "https://stream.example.com/morning",
                    ),
                )
            }

        receiver.onReceive(context, Intent().putExtra(AlarmReceiver.EXTRA_ALARM_ID, id))

        val startedService = shadowOf(context as Application).nextStartedService
        assertEquals("Morning FM", startedService?.getStringExtra(RadioPlaybackService.EXTRA_STATION_NAME))
        assertEquals(
            "https://stream.example.com/morning",
            startedService?.getStringExtra(RadioPlaybackService.EXTRA_STREAM_URL),
        )
    }

    @Test
    fun `onReceive does nothing when the alarm id doesn't exist`() {
        receiver.onReceive(context, Intent().putExtra(AlarmReceiver.EXTRA_ALARM_ID, 999L))

        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `onReceive does nothing when the identified alarm is disabled`() {
        val id =
            runBlocking {
                repository.insertAlarm(
                    WakeAlarm(
                        enabled = false,
                        hour = 7,
                        minute = 0,
                        stationName = "Morning FM",
                        streamUrl = "https://stream.example.com/morning",
                    ),
                )
            }

        receiver.onReceive(context, Intent().putExtra(AlarmReceiver.EXTRA_ALARM_ID, id))

        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `onReceive with no EXTRA_ALARM_ID falls back to migrating and using the legacy alarm`() {
        AlarmStateStore(context).save(
            AlarmStateStore.Alarm(
                enabled = true,
                hour = 7,
                minute = 0,
                stationName = "Legacy FM",
                streamUrl = "https://stream.example.com/legacy",
            ),
        )

        receiver.onReceive(context, Intent())

        val startedService = shadowOf(context as Application).nextStartedService
        assertEquals("Legacy FM", startedService?.getStringExtra(RadioPlaybackService.EXTRA_STATION_NAME))
        assertNull(AlarmStateStore(context).restore())
    }

    @Test
    fun `onReceive does nothing when no alarm and no legacy alarm exist`() {
        receiver.onReceive(context, Intent())

        assertNull(shadowOf(context as Application).nextStartedService)
    }
}
