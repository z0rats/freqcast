package com.freqcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freqcast.data.AlarmRepository
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.WakeAlarm
import com.freqcast.ui.playback.AlarmStateStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class AlarmEditUiState(
    val enabled: Boolean = false,
    val hour: Int = AlarmStateStore.DEFAULT_HOUR,
    val minute: Int = 0,
    val stations: List<RadioStation> = emptyList(),
    val selectedStation: RadioStation? = null,
    val isEditing: Boolean = false,
)

sealed interface AlarmEditEvent {
    data class Saved(
        val alarm: WakeAlarm,
    ) : AlarmEditEvent

    data class Deleted(
        val alarm: WakeAlarm,
    ) : AlarmEditEvent
}

class AlarmEditViewModel(
    private val alarmRepository: AlarmRepository,
    private val stationRepository: RadioStationRepository,
    private val editingAlarmId: Long?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlarmEditUiState(isEditing = editingAlarmId != null))
    val uiState: StateFlow<AlarmEditUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<AlarmEditEvent>(Channel.BUFFERED)
    val events: Flow<AlarmEditEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val stations = stationRepository.getAllStations()
            val existing = editingAlarmId?.let { alarmRepository.getAlarmById(it) }
            _uiState.value =
                _uiState.value.copy(
                    stations = stations,
                    enabled = existing?.enabled ?: false,
                    hour = existing?.hour ?: AlarmStateStore.DEFAULT_HOUR,
                    minute = existing?.minute ?: 0,
                    selectedStation =
                        stations.find { it.streamUrl == existing?.streamUrl } ?: stations.firstOrNull(),
                )
        }
    }

    fun onEnabledChange(value: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = value)
    }

    fun onTimeChange(
        hour: Int,
        minute: Int,
    ) {
        _uiState.value = _uiState.value.copy(hour = hour, minute = minute)
    }

    fun onStationSelected(station: RadioStation) {
        _uiState.value = _uiState.value.copy(selectedStation = station)
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            val station = state.selectedStation
            val alarm =
                WakeAlarm(
                    id = editingAlarmId ?: 0L,
                    enabled = state.enabled && station != null,
                    hour = state.hour,
                    minute = state.minute,
                    stationName = station?.name,
                    streamUrl = station?.streamUrl,
                )
            val savedId =
                if (editingAlarmId != null) {
                    alarmRepository.updateAlarm(alarm)
                    editingAlarmId
                } else {
                    alarmRepository.insertAlarm(alarm)
                }
            eventChannel.send(AlarmEditEvent.Saved(alarm.copy(id = savedId)))
        }
    }

    fun delete() {
        val id = editingAlarmId ?: return
        viewModelScope.launch {
            val alarm = alarmRepository.getAlarmById(id) ?: return@launch
            alarmRepository.deleteAlarm(id)
            eventChannel.send(AlarmEditEvent.Deleted(alarm))
        }
    }

    companion object {
        fun provideFactory(
            alarmRepository: AlarmRepository,
            stationRepository: RadioStationRepository,
            editingAlarmId: Long?,
        ): ViewModelProvider.Factory =
            viewModelFactory { AlarmEditViewModel(alarmRepository, stationRepository, editingAlarmId) }
    }
}
