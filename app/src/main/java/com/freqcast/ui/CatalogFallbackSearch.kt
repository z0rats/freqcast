package com.freqcast.ui

import android.content.Context
import android.util.Log
import com.freqcast.data.RadioBrowserApi
import com.freqcast.data.RadioBrowserStation
import com.freqcast.data.RadioBrowserStationInstaller
import com.freqcast.data.RadioStation
import com.freqcast.util.DebouncedSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Search-catalog fallback shown when [MainViewModel.filteredStations] comes up empty for a
 * non-trivial query — see [CatalogFallbackSearch.onLocalResultsChanged], exposed on
 * [MainViewModel.catalogFallback].
 */
data class CatalogFallbackState(
    val query: String = "",
    val results: List<RadioBrowserStation> = emptyList(),
    val isSearching: Boolean = false,
    val addedUrls: Set<String> = emptySet(),
)

/**
 * `MainViewModel`'s search-catalog fallback: when the local station-list search comes up empty
 * for a non-trivial query, debounces a Radio Browser directory search and offers to add straight
 * from the result — same install path as `DiscoverStationsViewModel.addStation`. Pulled out of
 * `MainViewModel` so its list/search/delete/reorder logic isn't interleaved with this unrelated,
 * self-contained concern.
 */
class CatalogFallbackSearch(
    private val scope: CoroutineScope,
    private val radioBrowserApi: RadioBrowserApi,
    private val installer: RadioBrowserStationInstaller,
    /** Called once a station is successfully added, so the caller can reload its own station list. */
    private val onStationAdded: () -> Unit,
) {
    private val _state = MutableStateFlow(CatalogFallbackState())
    val state: StateFlow<CatalogFallbackState> = _state.asStateFlow()
    private val search = DebouncedSearch<List<RadioBrowserStation>>(scope)

    /** Called whenever the local station-list search results change — runs, updates, or clears the fallback. */
    fun onLocalResultsChanged(
        localResults: List<RadioStation>,
        query: String,
    ) {
        if (localResults.isNotEmpty() || query.length < MIN_CATALOG_QUERY_LENGTH) {
            search.cancel()
            _state.value = CatalogFallbackState()
            return
        }
        // Already have a completed search for this exact query - e.g. the station list reloaded
        // for an unrelated reason (undoDelete, onResume) while the query itself didn't change.
        if (query == _state.value.query && _state.value.results.isNotEmpty()) return
        scheduleSearch(query)
    }

    private fun scheduleSearch(query: String) {
        search.launch(
            debounceMs = CATALOG_SEARCH_DEBOUNCE_MS,
            block = {
                _state.value = _state.value.copy(query = query, isSearching = true)
                radioBrowserApi.search(query, RadioBrowserApi.SearchBy.NAME, CATALOG_RESULT_LIMIT)
            },
            onResult = { result ->
                result.fold(
                    onSuccess = { results ->
                        _state.value = _state.value.copy(results = results, isSearching = false)
                    },
                    onFailure = { e ->
                        // Supplementary section - the user already sees the local "no results"
                        // state, so a network failure here just leaves the fallback empty rather
                        // than showing its own error.
                        Log.w(TAG, "catalog fallback search failed: query=\"$query\"", e)
                        _state.value = _state.value.copy(results = emptyList(), isSearching = false)
                    },
                )
            },
        )
    }

    /** Adds a Radio Browser search result from [state]. */
    fun addStation(
        context: Context,
        station: RadioBrowserStation,
    ) {
        if (_state.value.addedUrls.contains(station.url)) return
        scope.launch {
            if (installer.install(this, context.applicationContext, station)) {
                _state.value = _state.value.copy(addedUrls = _state.value.addedUrls + station.url)
                // Local list stops being empty for this query -> onLocalResultsChanged clears the
                // fallback section on its own; no manual sync needed here.
                onStationAdded()
            }
        }
    }

    companion object {
        private const val TAG = "CatalogFallbackSearch"

        // Below this, an almost-empty query would fan out to a huge, mostly-irrelevant catalog
        // result set for very little signal.
        private const val MIN_CATALOG_QUERY_LENGTH = 3
        private const val CATALOG_SEARCH_DEBOUNCE_MS = 400L
        private const val CATALOG_RESULT_LIMIT = 6
    }
}
