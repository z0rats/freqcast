package com.freqcast.ui

import androidx.room.Room
import com.freqcast.data.AlarmRepository
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.WakeAlarm
import com.freqcast.ui.playback.AlarmStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AlarmEditViewModelTest {
    private lateinit var database: AppDatabase
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var stationRepository: RadioStationRepository

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
                .allowMainThreadQueries()
                // See DiscoverStationsViewModelTest: keeps Room's suspend DAO calls off its own
                // real thread pool so they can't race the virtual test dispatcher.
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        alarmRepository = AlarmRepository(database.wakeAlarmDao())
        stationRepository = RadioStationRepository(database.radioStationDao())
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        scheduler: TestCoroutineScheduler,
        editingAlarmId: Long? = null,
    ): AlarmEditViewModel {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        return AlarmEditViewModel(alarmRepository, stationRepository, editingAlarmId)
    }

    private suspend fun TestScope.waitUntil(condition: () -> Boolean) {
        withTimeout(5000L) {
            while (!condition()) {
                advanceUntilIdle()
            }
        }
    }

    @Test
    fun `a new alarm defaults to disabled with the first station preselected`() =
        runTest {
            stationRepository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://rock"))
            val viewModel = createViewModel(testScheduler)

            waitUntil {
                viewModel.uiState.value.stations
                    .isNotEmpty()
            }

            assertFalse(viewModel.uiState.value.enabled)
            assertEquals(AlarmStateStore.DEFAULT_HOUR, viewModel.uiState.value.hour)
            assertEquals(
                "Rock FM",
                viewModel.uiState.value.selectedStation
                    ?.name,
            )
            assertFalse(viewModel.uiState.value.isEditing)
        }

    @Test
    fun `editing an existing alarm loads its saved values`() =
        runTest {
            stationRepository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://rock"))
            stationRepository.insertStation(RadioStation(name = "Jazz Radio", streamUrl = "http://jazz"))
            val id =
                alarmRepository.insertAlarm(
                    WakeAlarm(
                        enabled = true,
                        hour = 6,
                        minute = 15,
                        stationName = "Jazz Radio",
                        streamUrl = "http://jazz",
                    ),
                )
            val viewModel = createViewModel(testScheduler, editingAlarmId = id)

            waitUntil {
                viewModel.uiState.value.stations
                    .isNotEmpty()
            }

            assertTrue(viewModel.uiState.value.enabled)
            assertEquals(6, viewModel.uiState.value.hour)
            assertEquals(15, viewModel.uiState.value.minute)
            assertEquals(
                "Jazz Radio",
                viewModel.uiState.value.selectedStation
                    ?.name,
            )
            assertTrue(viewModel.uiState.value.isEditing)
        }

    @Test
    fun `save on a new alarm inserts a row and emits Saved with the final id`() =
        runTest {
            stationRepository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://rock"))
            val viewModel = createViewModel(testScheduler)
            waitUntil {
                viewModel.uiState.value.stations
                    .isNotEmpty()
            }
            val events = mutableListOf<AlarmEditEvent>()
            val job = launch { viewModel.events.collect { events.add(it) } }

            viewModel.onEnabledChange(true)
            viewModel.onTimeChange(7, 30)
            viewModel.save()
            waitUntil { events.isNotEmpty() }

            val savedEvent = events.single() as AlarmEditEvent.Saved
            val saved = savedEvent.alarm
            assertTrue(saved.enabled)
            assertTrue(savedEvent.shouldSchedule)
            assertEquals(7, saved.hour)
            assertEquals(30, saved.minute)
            assertEquals("Rock FM", saved.stationName)
            assertEquals(1, alarmRepository.getAllAlarms().size)
            job.cancel()
        }

    @Test
    fun `save with no stations available forces the alarm disabled`() =
        runTest {
            val viewModel = createViewModel(testScheduler)
            advanceUntilIdle() // let init's launch complete even with an empty station list
            val events = mutableListOf<AlarmEditEvent>()
            val job = launch { viewModel.events.collect { events.add(it) } }

            viewModel.onEnabledChange(true)
            viewModel.save()
            waitUntil { events.isNotEmpty() }

            val savedEvent = events.single() as AlarmEditEvent.Saved
            assertFalse(savedEvent.alarm.enabled)
            assertNull(savedEvent.alarm.stationName)
            assertFalse(savedEvent.shouldSchedule)
            job.cancel()
        }

    @Test
    fun `save on an existing alarm updates the same row rather than inserting a new one`() =
        runTest {
            stationRepository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://rock"))
            val id =
                alarmRepository.insertAlarm(
                    WakeAlarm(
                        enabled = false,
                        hour = 6,
                        minute = 0,
                        stationName = "Rock FM",
                        streamUrl = "http://rock",
                    ),
                )
            val viewModel = createViewModel(testScheduler, editingAlarmId = id)
            waitUntil {
                viewModel.uiState.value.stations
                    .isNotEmpty()
            }
            val events = mutableListOf<AlarmEditEvent>()
            val job = launch { viewModel.events.collect { events.add(it) } }

            viewModel.onEnabledChange(true)
            viewModel.save()
            waitUntil { events.isNotEmpty() }

            assertEquals(1, alarmRepository.getAllAlarms().size)
            assertEquals(id, (events.single() as AlarmEditEvent.Saved).alarm.id)
            assertTrue(alarmRepository.getAlarmById(id)!!.enabled)
            job.cancel()
        }

    @Test
    fun `delete removes the alarm and emits Deleted`() =
        runTest {
            stationRepository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://rock"))
            val id =
                alarmRepository.insertAlarm(
                    WakeAlarm(enabled = true, hour = 6, minute = 0, stationName = "Rock FM", streamUrl = "http://rock"),
                )
            val viewModel = createViewModel(testScheduler, editingAlarmId = id)
            waitUntil {
                viewModel.uiState.value.stations
                    .isNotEmpty()
            }
            val events = mutableListOf<AlarmEditEvent>()
            val job = launch { viewModel.events.collect { events.add(it) } }

            viewModel.delete()
            waitUntil { events.isNotEmpty() }

            assertEquals(id, (events.single() as AlarmEditEvent.Deleted).alarm.id)
            assertNull(alarmRepository.getAlarmById(id))
            job.cancel()
        }
}
