package com.freqcast.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freqcast.data.CuratedStations
import com.freqcast.data.RadioBrowserApi
import com.freqcast.data.RadioBrowserStation
import com.freqcast.data.RadioBrowserStationInstaller
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.playback.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

sealed interface MainScreenEvent {
    data class StationDeleted(
        val station: RadioStation,
    ) : MainScreenEvent
}

/**
 * Search-catalog fallback shown when [MainViewModel.filteredStations] comes up empty for a
 * non-trivial query — see [MainViewModel.onLocalResultsChanged].
 */
data class CatalogFallbackState(
    val query: String = "",
    val results: List<RadioBrowserStation> = emptyList(),
    val isSearching: Boolean = false,
    val addedUrls: Set<String> = emptySet(),
)

class MainViewModel(
    private val repository: RadioStationRepository,
    private val settingsStore: SettingsStore,
    // Same pattern as DiscoverStationsViewModel — a constructor default, no DI framework.
    private val radioBrowserApi: RadioBrowserApi = RadioBrowserApi(),
) : ViewModel() {
    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations: StateFlow<List<RadioStation>> = _stations.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _currentPlayingStationId = MutableStateFlow<Long?>(null)
    val currentPlayingStationId: StateFlow<Long?> = _currentPlayingStationId.asStateFlow()

    // Non-null for exactly one station, right after CuratedStations.pack is seeded on a fresh
    // install - StationListPane plays a one-time swipe-to-reveal peek animation on that station,
    // then calls clearSwipeHint(). See SettingsStore.hasShownSwipeHint for why this only ever
    // fires once per install.
    private val _swipeHintStationId = MutableStateFlow<Long?>(null)
    val swipeHintStationId: StateFlow<Long?> = _swipeHintStationId.asStateFlow()

    private val eventChannel = Channel<MainScreenEvent>(Channel.BUFFERED)
    val events: Flow<MainScreenEvent> = eventChannel.receiveAsFlow()

    private val _catalogFallback = MutableStateFlow(CatalogFallbackState())
    val catalogFallback: StateFlow<CatalogFallbackState> = _catalogFallback.asStateFlow()
    private var catalogSearchJob: Job? = null
    private val installer = RadioBrowserStationInstaller(repository, radioBrowserApi)

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
        // Falls back to a debounced search of the Radio Browser directory whenever the local
        // search comes up empty - see onLocalResultsChanged. addStationFromCatalog()'s
        // loadStations() re-triggers this same collector, which is what clears the fallback once
        // an added station makes the local search non-empty again.
        viewModelScope.launch {
            filteredStations.collect(::onLocalResultsChanged)
        }
    }

    private fun onLocalResultsChanged(localResults: List<RadioStation>) {
        val query = _searchQuery.value.trim()
        if (localResults.isNotEmpty() || query.length < MIN_CATALOG_QUERY_LENGTH) {
            catalogSearchJob?.cancel()
            _catalogFallback.value = CatalogFallbackState()
            return
        }
        // Already have a completed search for this exact query - e.g. _stations reloaded for an
        // unrelated reason (undoDelete, onResume) while the query itself didn't change.
        if (query == _catalogFallback.value.query && _catalogFallback.value.results.isNotEmpty()) return
        scheduleCatalogSearch(query)
    }

    private fun scheduleCatalogSearch(query: String) {
        catalogSearchJob?.cancel()
        catalogSearchJob =
            viewModelScope.launch {
                delay(CATALOG_SEARCH_DEBOUNCE_MS)
                runCatalogSearch(query)
            }
    }

    private suspend fun runCatalogSearch(query: String) {
        _catalogFallback.value = _catalogFallback.value.copy(query = query, isSearching = true)
        try {
            val results = radioBrowserApi.search(query, RadioBrowserApi.SearchBy.NAME, CATALOG_RESULT_LIMIT)
            // An in-flight search superseded by a newer query may not have surfaced its
            // cancellation yet by the time it returns - check explicitly rather than let a stale
            // search's results overwrite whatever a newer one already put in _catalogFallback.
            coroutineContext.ensureActive()
            _catalogFallback.value = _catalogFallback.value.copy(results = results, isSearching = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Supplementary section - the user already sees the local "no results" state, so a
            // network failure here just leaves the fallback empty rather than showing its own error.
            Log.w(TAG, "catalog fallback search failed: query=\"$query\"", e)
            _catalogFallback.value = _catalogFallback.value.copy(results = emptyList(), isSearching = false)
        }
    }

    /** Adds a Radio Browser search result from [catalogFallback] - same install path as DiscoverStationsViewModel.addStation. */
    fun addStationFromCatalog(
        context: Context,
        station: RadioBrowserStation,
    ) {
        if (_catalogFallback.value.addedUrls.contains(station.url)) return
        viewModelScope.launch {
            if (installer.install(this, context.applicationContext, station)) {
                _catalogFallback.value =
                    _catalogFallback.value.copy(addedUrls = _catalogFallback.value.addedUrls + station.url)
                // Local list stops being empty for this query -> onLocalResultsChanged clears the
                // fallback section on its own; no manual sync needed here.
                loadStations()
            }
        }
    }

    fun loadStations() {
        viewModelScope.launch {
            _stations.value = repository.getAllStations()
        }
    }

    /**
     * Inserts [CuratedStations.pack] the first time this runs on a given install (guarded by
     * [SettingsStore.hasSeededCuratedPack], so it never re-runs after a user deletes some or all
     * of the pack). Called once from [MainActivity]'s startup effect, before the first [loadStations].
     * [context] resolves each entry's bundled icon via [CuratedStations.withResolvedIcon] - not
     * stored on the ViewModel, same as [SettingsViewModel.importStations]'s per-call Context.
     */
    suspend fun seedCuratedStationsIfNeeded(context: Context) {
        if (settingsStore.hasSeededCuratedPack) return
        val insertedIds =
            CuratedStations.pack.map { station ->
                repository.insertStation(CuratedStations.withResolvedIcon(context, station))
            }
        settingsStore.hasSeededCuratedPack = true

        if (!settingsStore.hasShownSwipeHint) {
            // The second entry, not the first - purely a visual choice (demonstrating the gesture
            // on the very first row read as "the whole list works this way" rather than pointing
            // at one specific station). Falls back to the first if the pack ever shrinks to 1.
            _swipeHintStationId.value = insertedIds.getOrNull(1) ?: insertedIds.firstOrNull()
            settingsStore.hasShownSwipeHint = true
        }
    }

    /** Called once the one-time swipe hint animation (see [swipeHintStationId]) has played. */
    fun clearSwipeHint() {
        _swipeHintStationId.value = null
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
        private const val TAG = "MainViewModel"

        // Below this, an almost-empty query would fan out to a huge, mostly-irrelevant catalog
        // result set for very little signal.
        private const val MIN_CATALOG_QUERY_LENGTH = 3
        private const val CATALOG_SEARCH_DEBOUNCE_MS = 400L
        private const val CATALOG_RESULT_LIMIT = 6

        fun provideFactory(
            repository: RadioStationRepository,
            settingsStore: SettingsStore,
        ): ViewModelProvider.Factory = viewModelFactory { MainViewModel(repository, settingsStore) }
    }
}
