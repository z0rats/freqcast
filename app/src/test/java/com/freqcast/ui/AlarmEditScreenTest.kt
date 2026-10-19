package com.freqcast.ui

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.data.AlarmRepository
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.WakeAlarm
import com.freqcast.testing.composeHostRuleChain
import com.freqcast.testing.inMemoryDatabase
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
import org.robolectric.shadows.ShadowToast

/**
 * [AlarmEditScreen] over a real [AlarmEditViewModel] + in-memory Room: the form, and what a save
 * or delete actually does to the database, the [AlarmManager] schedule and the user feedback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AlarmEditScreenTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var alarms: AlarmRepository
    private lateinit var stations: RadioStationRepository
    private val alarmManager get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    private var doneCount = 0
    private var backCount = 0

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        alarms = AlarmRepository(database.wakeAlarmDao())
        stations = RadioStationRepository(database.radioStationDao())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() = database.close()

    private fun seedStations() =
        runBlocking {
            stations.insertStation(RadioStation(name = "Jazz FM", streamUrl = "https://jazz"))
            stations.insertStation(RadioStation(name = "Rock FM", streamUrl = "https://rock"))
        }

    private fun show(editingId: Long? = null) {
        val viewModel = AlarmEditViewModel(alarms, stations, editingId)
        composeTestRule.setContent {
            AlarmEditScreen(viewModel = viewModel, onDone = { doneCount++ }, onBackClick = { backCount++ })
        }
        composeTestRule.waitForIdle()
    }

    private fun savedAlarms() = runBlocking { alarms.getAllAlarms() }

    private fun clickSave() {
        // "Save" also labels the time dialog's confirm button, but that dialog is closed here.
        composeTestRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `without any stations the form is replaced by a hint and the switch is disabled`() {
        show()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_new_title)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_no_stations)).assertExists()
        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
        composeTestRule.onNodeWithText(context.getString(R.string.save)).assertDoesNotExist()
    }

    @Test
    fun `new enabled alarm for a picked station is saved, scheduled and confirmed`() {
        seedStations()
        show()
        composeTestRule.onNodeWithText("Jazz FM").performClick() // defaults to the first station
        composeTestRule.onNodeWithText("Rock FM").performClick()
        composeTestRule.onNode(isToggleable()).performClick()
        composeTestRule.onNode(isToggleable()).assertIsOn()

        clickSave()

        val saved = savedAlarms().single()
        assertTrue(saved.enabled)
        assertEquals("Rock FM", saved.stationName)
        assertEquals("https://rock", saved.streamUrl)
        assertEquals(1, alarmManager.scheduledAlarms.size)
        assertEquals(context.getString(R.string.alarm_enabled_toast, "07:00"), ShadowToast.getTextOfLatestToast())
        assertEquals(1, doneCount)
    }

    @Test
    fun `saving a disabled alarm cancels it and says so`() {
        seedStations()
        show()
        clickSave()

        assertFalse(savedAlarms().single().enabled)
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
        assertEquals(context.getString(R.string.alarm_disabled_toast), ShadowToast.getTextOfLatestToast())
        assertEquals(1, doneCount)
    }

    @Test
    @Config(sdk = [31])
    fun `without exact-alarm permission, saving asks for it and opens the system setting`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        seedStations()
        show()
        composeTestRule.onNode(isToggleable()).performClick()
        clickSave()

        assertEquals(context.getString(R.string.alarm_permission_needed), ShadowToast.getTextOfLatestToast())
        val opened = shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, opened.action)
        assertEquals("package:${context.packageName}", opened.dataString)
        assertEquals(1, doneCount)
    }

    @Test
    fun `editing loads the existing alarm, and delete removes it`() {
        seedStations()
        val id =
            runBlocking {
                alarms.insertAlarm(
                    WakeAlarm(
                        enabled = true,
                        hour = 6,
                        minute = 30,
                        stationName = "Rock FM",
                        streamUrl = "https://rock",
                    ),
                )
            }
        show(editingId = id)

        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_title)).assertExists()
        composeTestRule.onNodeWithText("06:30").assertExists()
        composeTestRule.onNodeWithText("Rock FM").assertExists()
        composeTestRule.onNode(isToggleable()).assertIsOn()

        composeTestRule.onNodeWithText(context.getString(R.string.delete)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(savedAlarms().isEmpty())
        assertEquals(1, doneCount)
    }

    @Test
    fun `time row opens the picker, and confirming it keeps the chosen time`() {
        seedStations()
        show()
        composeTestRule.onNodeWithText("07:00").performClick()
        composeTestRule.onAllNodesWithText(context.getString(R.string.alarm_time)).assertCountEquals(2)

        composeTestRule.onNodeWithText(context.getString(R.string.close)).performClick()
        composeTestRule.onAllNodesWithText(context.getString(R.string.alarm_time)).assertCountEquals(1)

        composeTestRule.onNodeWithText("07:00").performClick()
        composeTestRule.onAllNodesWithText(context.getString(R.string.save))[1].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("07:00").assertExists()
    }

    @Test
    fun `back button calls back`() {
        show()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(1, backCount)
    }
}
