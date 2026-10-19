package com.freqcast.ui

import android.app.AlarmManager
import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.data.AlarmRepository
import com.freqcast.data.AppDatabase
import com.freqcast.data.WakeAlarm
import com.freqcast.testing.composeHostRuleChain
import com.freqcast.testing.inMemoryDatabase
import com.freqcast.ui.playback.AlarmStateStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * [AlarmListScreen] over a real [AlarmListViewModel] + in-memory Room: what the list shows, and
 * that toggling/deleting both persists and (un)schedules the real [AlarmManager] alarm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AlarmListScreenTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: AlarmRepository
    private val alarmManager get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = AlarmRepository(database.wakeAlarmDao())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() = database.close()

    private fun show(vararg alarms: WakeAlarm): AlarmListViewModel {
        runBlocking { alarms.forEach { repository.insertAlarm(it) } }
        val viewModel = AlarmListViewModel(repository, AlarmStateStore(context))
        composeTestRule.setContent {
            AlarmListScreen(
                viewModel = viewModel,
                onBackClick = { calls += "back" },
                onAddClick = { calls += "add" },
                onAlarmClick = { calls += "open:${it.id}" },
            )
        }
        viewModel.loadAlarms()
        composeTestRule.waitForIdle()
        return viewModel
    }

    private fun alarms() = runBlocking { repository.getAllAlarms() }

    @Test
    fun `empty list shows the no-alarms hint and the add and back buttons work`() {
        show()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_none)).assertExists()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.alarm_add)).performClick()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(listOf("add", "back"), calls)
    }

    @Test
    fun `rows show time and station, a missing station shows the select prompt`() {
        show(
            WakeAlarm(id = 1, enabled = true, hour = 7, minute = 5, stationName = "Jazz FM", streamUrl = "https://j"),
            WakeAlarm(id = 2, enabled = false, hour = 21, minute = 30, stationName = null, streamUrl = null),
        )
        composeTestRule.onNodeWithText("07:05").assertExists()
        composeTestRule.onNodeWithText("Jazz FM").assertExists()
        composeTestRule.onNodeWithText("21:30").assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_select_station)).assertExists()

        composeTestRule.onNodeWithText("Jazz FM").performClick()
        assertEquals(listOf("open:1"), calls)
    }

    @Test
    fun `switching an alarm on persists and schedules it, off cancels it`() {
        show(WakeAlarm(id = 1, enabled = false, hour = 7, minute = 0, stationName = "Jazz FM", streamUrl = "https://j"))
        val toggle = composeTestRule.onNode(isToggleable())
        toggle.assertIsOff()

        toggle.performClick()
        composeTestRule.waitForIdle()
        toggle.assertIsOn()
        assertTrue(alarms().single().enabled)
        assertEquals(1, alarmManager.scheduledAlarms.size)

        toggle.performClick()
        composeTestRule.waitForIdle()
        assertFalse(alarms().single().enabled)
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `enabling an alarm with no station persists but schedules nothing`() {
        show(WakeAlarm(id = 1, enabled = false, hour = 7, minute = 0, stationName = null, streamUrl = null))
        composeTestRule.onNode(isToggleable()).performClick()
        composeTestRule.waitForIdle()
        assertTrue(alarms().single().enabled)
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `delete asks for confirmation - cancel keeps it, confirm removes it`() {
        show(WakeAlarm(id = 1, enabled = true, hour = 7, minute = 0, stationName = "Jazz FM", streamUrl = "https://j"))
        val deleteLabel = context.getString(R.string.alarm_delete)

        composeTestRule.onNodeWithContentDescription(deleteLabel).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_delete_confirm)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, alarms().size)

        composeTestRule.onNodeWithContentDescription(deleteLabel).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.delete)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(alarms().isEmpty())
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_none)).assertExists()
    }

    @Test
    fun `a migrated legacy alarm is rescheduled under its new id`() {
        // Pre-multi-alarm single alarm, as the old settings screen saved it.
        AlarmStateStore(context).save(AlarmStateStore.Alarm(true, 6, 45, "Jazz FM", "https://j"))
        show()
        composeTestRule.onNodeWithText("06:45").assertExists()
        assertEquals(1, alarmManager.scheduledAlarms.size)
    }
}
