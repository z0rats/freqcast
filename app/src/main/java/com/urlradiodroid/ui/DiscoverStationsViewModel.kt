package com.urlradiodroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.urlradiodroid.R
import com.urlradiodroid.data.RadioBrowserApi
import com.urlradiodroid.data.RadioBrowserStation
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DiscoverSearchMode { NAME, GENRE, COUNTRY }

data class DiscoverStationsUiState(
    val query: String = "",
    val mode: DiscoverSearchMode = DiscoverSearchMode.NAME,
    val results: List<RadioBrowserStation> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val errorRes: Int? = null,
    val addedUrls: Set<String> = emptySet(),
)

class DiscoverStationsViewModel(
    private val repository: RadioStationRepository,
    private val api: RadioBrowserApi = RadioBrowserApi(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiscoverStationsUiState())
    val uiState: StateFlow<DiscoverStationsUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val existingUrls = repository.getAllStations().map { it.streamUrl }.toSet()
            _uiState.value = _uiState.value.copy(addedUrls = existingUrls)
        }
    }

    fun onQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
        scheduleSearch()
    }

    fun onModeChange(mode: DiscoverSearchMode) {
        if (mode == _uiState.value.mode) return
        _uiState.value = _uiState.value.copy(mode = mode)
        scheduleSearch()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) {
            _uiState.value =
                _uiState.value.copy(results = emptyList(), isSearching = false, hasSearched = false, errorRes = null)
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                runSearch(query)
            }
    }

    private suspend fun runSearch(query: String) {
        _uiState.value = _uiState.value.copy(isSearching = true, errorRes = null)
        val searchBy =
            when (_uiState.value.mode) {
                DiscoverSearchMode.NAME -> RadioBrowserApi.SearchBy.NAME
                DiscoverSearchMode.GENRE -> RadioBrowserApi.SearchBy.TAG
                DiscoverSearchMode.COUNTRY -> RadioBrowserApi.SearchBy.COUNTRY
            }
        try {
            val results = api.search(query, searchBy)
            _uiState.value = _uiState.value.copy(results = results, isSearching = false, hasSearched = true)
        } catch (e: Exception) {
            _uiState.value =
                _uiState.value.copy(
                    results = emptyList(),
                    isSearching = false,
                    hasSearched = true,
                    errorRes = R.string.discover_search_error,
                )
        }
    }

    fun addStation(station: RadioBrowserStation) {
        if (_uiState.value.addedUrls.contains(station.url)) return
        viewModelScope.launch {
            if (repository.isUrlTaken(station.url)) {
                _uiState.value = _uiState.value.copy(addedUrls = _uiState.value.addedUrls + station.url)
                return@launch
            }
            var name = station.name
            var suffix = 2
            while (repository.isNameTaken(name)) {
                name = "${station.name} ($suffix)"
                suffix++
            }
            try {
                repository.insertStation(RadioStation(name = name, streamUrl = station.url, customIcon = null))
                _uiState.value = _uiState.value.copy(addedUrls = _uiState.value.addedUrls + station.url)
            } catch (e: Exception) {
                // Defense-in-depth unique constraints (see AppDatabase) can still race with the
                // isUrlTaken/isNameTaken checks above; leave the station unmarked so the user can retry.
            }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 400L

        fun provideFactory(repository: RadioStationRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DiscoverStationsViewModel(repository) as T
            }
    }
}
