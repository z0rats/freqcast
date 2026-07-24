package com.freqcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface MainScreenEvent {
    data class StationDeleted(
        val station: RadioStation,
    ) : MainScreenEvent
}

class MainViewModel(
    private val repository: RadioStationRepository,
) : ViewModel() {
    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations: StateFlow<List<RadioStation>> = _stations.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _currentPlayingStationId = MutableStateFlow<Long?>(null)
    val currentPlayingStationId: StateFlow<Long?> = _currentPlayingStationId.asStateFlow()

    private val eventChannel = Channel<MainScreenEvent>(Channel.BUFFERED)
    val events: Flow<MainScreenEvent> = eventChannel.receiveAsFlow()

    val filteredStations =
        combine(_stations, _searchQuery) { stations, query ->
            if (query.isBlank()) {
                stations
            } else {
                val queryLower = query.lowercase().trim()
                stations.filter { station ->
                    station.name.lowercase().contains(queryLower) ||
                        station.streamUrl.lowercase() == queryLower ||
                        station.description?.lowercase()?.contains(queryLower) == true
                }
            }
        }

    init {
        loadStations()
    }

    fun loadStations() {
        viewModelScope.launch {
            _stations.value = repository.getAllStations()
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateCurrentPlayingStation(stationId: Long?) {
        _currentPlayingStationId.value = stationId
    }

    fun getCurrentPlayingStationId(): Long? = _currentPlayingStationId.value

    /** Deletes immediately, but keeps [station] around so [undoDelete] can restore it. */
    fun deleteStation(station: RadioStation) {
        viewModelScope.launch {
            repository.deleteStation(station.id)
            loadStations()
            eventChannel.send(MainScreenEvent.StationDeleted(station))
        }
    }

    /** Re-inserts [station] with its original id and sortOrder, restoring its position in the list. */
    fun undoDelete(station: RadioStation) {
        viewModelScope.launch {
            repository.restoreStation(station)
            loadStations()
        }
    }

    /**
     * Moves the station at [fromIndex] to [toIndex] in the in-memory list only — called on every
     * intermediate step of a drag gesture for instant visual feedback, without a DB write per
     * frame. [persistStationOrder] commits the final order once the drag ends.
     */
    fun moveStation(
        fromIndex: Int,
        toIndex: Int,
    ) {
        val current = _stations.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        _stations.value =
            current.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
    }

    /** Persists the current in-memory order as each station's new `sortOrder`. */
    fun persistStationOrder() {
        viewModelScope.launch {
            repository.updateSortOrder(_stations.value.map { it.id })
        }
    }

    companion object {
        fun provideFactory(repository: RadioStationRepository): ViewModelProvider.Factory =
            viewModelFactory { MainViewModel(repository) }
    }
}
