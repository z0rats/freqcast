package com.freqcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freqcast.data.AlarmRepository
import com.freqcast.data.WakeAlarm
import com.freqcast.ui.playback.AlarmStateStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface AlarmListEvent {
    /**
     * A pre-multi-alarm single alarm was just imported into the alarms table on this load — the
     * screen must cancel its old-style [android.app.AlarmManager] registration
     * ([AlarmScheduler.cancelLegacy]) and, if it was enabled, schedule it under its new per-row id
     * (see [AlarmRepository.migrateLegacyAlarmIfNeeded]).
     */
    data class LegacyAlarmMigrated(
        val alarm: WakeAlarm,
    ) : AlarmListEvent
}

class AlarmListViewModel(
    private val repository: AlarmRepository,
    private val legacyStore: AlarmStateStore,
) : ViewModel() {
    private val _alarms = MutableStateFlow<List<WakeAlarm>>(emptyList())
    val alarms: StateFlow<List<WakeAlarm>> = _alarms.asStateFlow()

    private val eventChannel = Channel<AlarmListEvent>(Channel.BUFFERED)
    val events: Flow<AlarmListEvent> = eventChannel.receiveAsFlow()

    fun loadAlarms() {
        viewModelScope.launch {
            val migrated = repository.migrateLegacyAlarmIfNeeded(legacyStore)
            _alarms.value = repository.getAllAlarms()
            migrated?.let { eventChannel.send(AlarmListEvent.LegacyAlarmMigrated(it)) }
        }
    }

    fun setEnabled(
        alarm: WakeAlarm,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            repository.updateAlarm(alarm.copy(enabled = enabled))
            _alarms.value = repository.getAllAlarms()
        }
    }

    fun deleteAlarm(alarm: WakeAlarm) {
        viewModelScope.launch {
            repository.deleteAlarm(alarm.id)
            _alarms.value = repository.getAllAlarms()
        }
    }

    companion object {
        fun provideFactory(
            repository: AlarmRepository,
            legacyStore: AlarmStateStore,
        ): ViewModelProvider.Factory = viewModelFactory { AlarmListViewModel(repository, legacyStore) }
    }
}
