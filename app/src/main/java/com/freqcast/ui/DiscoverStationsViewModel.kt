package com.freqcast.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freqcast.R
import com.freqcast.data.RadioBrowserApi
import com.freqcast.data.RadioBrowserStation
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.util.IconStorage
import com.freqcast.util.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DiscoverSearchMode { NAME, GENRE, COUNTRY, LANGUAGE, NEARBY }

data class DiscoverStationsUiState(
    val query: String = "",
    val mode: DiscoverSearchMode = DiscoverSearchMode.NAME,
    val results: List<RadioBrowserStation> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val errorRes: Int? = null,
    val addedUrls: Set<String> = emptySet(),
    val locationPermissionDenied: Boolean = false,
)

class DiscoverStationsViewModel(
    private val repository: RadioStationRepository,
    private val appContext: Context,
    private val api: RadioBrowserApi = RadioBrowserApi(),
    private val locationProvider: LocationProvider = LocationProvider(appContext),
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
        searchJob?.cancel()
        _uiState.value =
            _uiState.value.copy(
                mode = mode,
                results = emptyList(),
                isSearching = false,
                hasSearched = false,
                errorRes = null,
                locationPermissionDenied = false,
            )
        // NEARBY has no text query to debounce on — the screen checks/requests location
        // permission first, then calls searchNearby() directly once granted.
        if (mode != DiscoverSearchMode.NEARBY) scheduleSearch()
    }

    /** Called by the screen once ACCESS_COARSE_LOCATION is confirmed granted. */
    fun searchNearby() {
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(isSearching = true, errorRes = null, locationPermissionDenied = false)
                val location = locationProvider.getCurrentLocation()
                if (location == null) {
                    _uiState.value =
                        _uiState.value.copy(
                            isSearching = false,
                            hasSearched = true,
                            errorRes = R.string.discover_location_unavailable,
                        )
                    return@launch
                }
                try {
                    val results =
                        api.searchNearby(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            radiusMeters = NEARBY_RADIUS_METERS,
                        )
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
    }

    /** The screen calls this when the user declines the ACCESS_COARSE_LOCATION request. */
    fun onLocationPermissionDenied() {
        _uiState.value = _uiState.value.copy(locationPermissionDenied = true)
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
        // NEARBY is driven by searchNearby(), never by the debounced text-query path.
        if (_uiState.value.mode == DiscoverSearchMode.NEARBY) return
        _uiState.value = _uiState.value.copy(isSearching = true, errorRes = null)
        val searchBy =
            when (_uiState.value.mode) {
                DiscoverSearchMode.NAME -> RadioBrowserApi.SearchBy.NAME
                DiscoverSearchMode.GENRE -> RadioBrowserApi.SearchBy.TAG
                DiscoverSearchMode.COUNTRY -> RadioBrowserApi.SearchBy.COUNTRY
                DiscoverSearchMode.LANGUAGE -> RadioBrowserApi.SearchBy.LANGUAGE
                DiscoverSearchMode.NEARBY -> return
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
            val name = repository.uniqueName(station.name)
            try {
                val stationId =
                    repository.insertStation(
                        RadioStation(
                            name = name,
                            streamUrl = station.url,
                            customIcon = null,
                            description = station.tags.takeIf { it.isNotBlank() },
                            isHls = station.hls,
                            radioBrowserUuid = station.uuid.takeIf { it.isNotBlank() },
                        ),
                    )
                _uiState.value = _uiState.value.copy(addedUrls = _uiState.value.addedUrls + station.url)
                // Fire-and-forget: fills in the station's real logo once downloaded, in the
                // background, rather than blocking the "Added" checkmark on a network round-trip.
                // Falls back to the auto-generated emoji (already showing) if the favicon is
                // missing/unreachable, or if this ViewModel's scope is gone before it finishes.
                station.favicon.takeIf { it.isNotBlank() }?.let { faviconUrl ->
                    launch { downloadAndSetFavicon(stationId, faviconUrl) }
                }
            } catch (e: Exception) {
                // Defense-in-depth unique constraints (see AppDatabase) can still race with the
                // isUrlTaken/isNameTaken checks above; leave the station unmarked so the user can retry.
            }
        }
    }

    private suspend fun downloadAndSetFavicon(
        stationId: Long,
        faviconUrl: String,
    ) {
        val bytes = api.downloadFavicon(faviconUrl) ?: return
        val path = withContext(Dispatchers.IO) { IconStorage.saveImageBytes(appContext, bytes) } ?: return
        repository.getStationById(stationId)?.let { current ->
            repository.updateStation(current.copy(customIcon = path))
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 400L
        private const val NEARBY_RADIUS_METERS = 50_000

        fun provideFactory(
            repository: RadioStationRepository,
            context: Context,
        ): ViewModelProvider.Factory =
            viewModelFactory { DiscoverStationsViewModel(repository, context.applicationContext) }
    }
}
