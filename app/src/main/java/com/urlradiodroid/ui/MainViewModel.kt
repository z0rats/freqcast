package com.urlradiodroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.urlradiodroid.data.ImportResult
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: RadioStationRepository,
) : ViewModel() {
    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations: StateFlow<List<RadioStation>> = _stations.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _currentPlayingStationId = MutableStateFlow<Long?>(null)
    val currentPlayingStationId: StateFlow<Long?> = _currentPlayingStationId.asStateFlow()

    val filteredStations =
        combine(_stations, _searchQuery) { stations, query ->
            if (query.isBlank()) {
                stations
            } else {
                val queryLower = query.lowercase().trim()
                stations.filter { station ->
                    station.name.lowercase().contains(queryLower) ||
                        station.streamUrl.lowercase() == queryLower
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

    fun deleteStation(stationId: Long) {
        viewModelScope.launch {
            repository.deleteStation(stationId)
            loadStations()
        }
    }

    /** Plain suspend function (not launched internally) so callers get the JSON back to write to a file. */
    suspend fun exportStationsJson(): String = repository.exportStationsToJson()

    /** Plain suspend function so callers get the [ImportResult] back to show to the user. */
    suspend fun importStationsJson(json: String): ImportResult {
        val result = repository.importStationsFromJson(json)
        loadStations()
        return result
    }

    companion object {
        fun provideFactory(repository: RadioStationRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository) as T
            }
    }
}
