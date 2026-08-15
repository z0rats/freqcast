package com.freqcast.ui

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freqcast.R
import com.freqcast.data.RadioBrowserApi
import com.freqcast.data.RadioBrowserStation
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.RadioTag
import com.freqcast.data.RadioTagRepository
import com.freqcast.util.CountryCatalog
import com.freqcast.util.IconStorage
import com.freqcast.util.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class DiscoverSearchMode { NAME, GENRE, COUNTRY, NEARBY }

data class DiscoverStationsUiState(
    val query: String = "",
    val mode: DiscoverSearchMode = DiscoverSearchMode.NAME,
    val results: List<RadioBrowserStation> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val errorRes: Int? = null,
    val addedUrls: Set<String> = emptySet(),
    val locationPermissionDenied: Boolean = false,
    val defaultBrowseResults: List<RadioBrowserStation> = emptyList(),
    val defaultBrowseRegionCode: String? = null,
    val isLoadingDefaultBrowse: Boolean = false,
    val selectedGenreTag: String? = null,
    val tagSuggestions: List<String> = emptyList(),
)

class DiscoverStationsViewModel(
    private val repository: RadioStationRepository,
    private val appContext: Context,
    private val api: RadioBrowserApi = RadioBrowserApi(),
    private val locationProvider: LocationProvider = LocationProvider(appContext),
    private val tagRepository: RadioTagRepository = RadioTagRepository.create(appContext),
    // Off by default in tests (see DiscoverStationsViewModelTest.createViewModel) so the many
    // tests asserting an exact server.requestCount/response ordering for their own action don't
    // also have to account for this unconditional init-time network call.
    private val autoLoadDefaultBrowse: Boolean = true,
    // Same rationale as autoLoadDefaultBrowse - off by default in tests.
    private val autoSyncTags: Boolean = true,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiscoverStationsUiState())
    val uiState: StateFlow<DiscoverStationsUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    init {
        viewModelScope.launch {
            val existingUrls = repository.getAllStations().map { it.streamUrl }.toSet()
            _uiState.value = _uiState.value.copy(addedUrls = existingUrls)
        }
        if (autoLoadDefaultBrowse) loadDefaultBrowse()
        if (autoSyncTags) syncTagsIfNeeded()
    }

    /**
     * One-time-per-app-launch background sync of the directory's full tag catalog into
     * [tagRepository]'s local cache, so the GENRE tab's autocomplete ([updateTagSuggestions])
     * works offline afterwards. Gated on the cache holding at least [MIN_HEALTHY_TAG_COUNT] rows,
     * not just being non-empty: the real directory currently has ~11.9k tags, but a bare
     * `/json/tags` call with no `limit` silently truncates to the server's default 1000 rows
     * (confirmed against the live API - see [RadioBrowserApi.fetchTags]'s doc) - an
     * emptiness-only check would treat that stale, truncated cache as "already synced" forever
     * and never retry, which is exactly what happened before [RadioBrowserApi.fetchTags] started
     * passing an explicit limit. A failed/incomplete fetch just leaves the count below threshold,
     * so the next launch retries automatically without needing any separate "last synced" flag.
     */
    private fun syncTagsIfNeeded() {
        viewModelScope.launch {
            val cachedCount = tagRepository.count()
            if (cachedCount >= MIN_HEALTHY_TAG_COUNT) {
                Log.d(TAG, "syncTagsIfNeeded: cache already has $cachedCount tags, skipping")
                return@launch
            }
            Log.d(TAG, "syncTagsIfNeeded: cache has only $cachedCount tags, (re)fetching from directory")
            try {
                val tags = api.fetchTags()
                Log.d(TAG, "syncTagsIfNeeded: fetched ${tags.size} tags")
                if (tags.size >= MIN_HEALTHY_TAG_COUNT) {
                    tagRepository.replaceAll(tags.map { RadioTag(tag = it.name, stationCount = it.stationCount) })
                    Log.d(TAG, "syncTagsIfNeeded: cache populated with ${tags.size} tags")
                } else {
                    Log.w(TAG, "syncTagsIfNeeded: fetched only ${tags.size} tags, leaving stale cache in place")
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncTagsIfNeeded failed", e)
            }
        }
    }

    /**
     * Discover should never open onto a bare "type something to search" prompt — this pre-loads a
     * short list of popular stations (in the resolved region if any, else worldwide) shown by the
     * screen whenever the user hasn't actively searched yet, regardless of which mode chip is
     * selected.
     */
    private fun loadDefaultBrowse() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDefaultBrowse = true)
            // regionCode drives both the API query (via its English name, what radio-browser.info
            // expects) and the header text the screen shows (localized to the app's display
            // language) — kept as the raw code here so the screen can format its own localized name
            // rather than this ViewModel hardcoding an English-only label.
            val regionCode = resolveDefaultBrowseRegionCode()
            val countryName = regionCode?.let { CountryCatalog.englishNameForRegion(it) }
            var resolvedRegionCode: String? = null
            val results =
                try {
                    val byCountry =
                        countryName?.let {
                            api.search(
                                it,
                                RadioBrowserApi.SearchBy.COUNTRY,
                                DEFAULT_BROWSE_LIMIT,
                            )
                        }
                    if (!byCountry.isNullOrEmpty()) {
                        resolvedRegionCode = regionCode
                        byCountry
                    } else {
                        api.topStations(DEFAULT_BROWSE_LIMIT)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "loadDefaultBrowse failed", e)
                    emptyList()
                }
            _uiState.value =
                _uiState.value.copy(
                    defaultBrowseResults = results,
                    defaultBrowseRegionCode = resolvedRegionCode,
                    isLoadingDefaultBrowse = false,
                )
        }
    }

    /**
     * An explicit in-app language pick (Settings' language picker, [AppCompatDelegate.getApplicationLocales])
     * is a stronger, more deliberate signal of what stations the user wants than the device's
     * underlying system region — e.g. switching the app to Russian should browse Russian stations
     * by default even on a device whose system region is still "US"; browsing whatever the system
     * region happened to be read as "random" unrelated stations to a user who'd just picked
     * Russian. The picker's tags carry language only, no region (`"ru"`, `"es"`), except `"zh-CN"`
     * which already has one — [LANGUAGE_TO_DEFAULT_REGION] fills in the gap for the others.
     * "System default" ([AppCompatDelegate.getApplicationLocales] empty) falls through to the raw
     * system region unchanged, same as before this override existed.
     */
    private fun resolveDefaultBrowseRegionCode(): String? {
        val appLocale = AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)
        val appLocaleRegion =
            appLocale?.country?.takeIf { it.isNotBlank() }
                ?: appLocale?.language?.let { LANGUAGE_TO_DEFAULT_REGION[it] }
        return appLocaleRegion
            ?: Resources
                .getSystem()
                .configuration.locales
                .get(0)
                .country
                .takeIf { it.isNotBlank() }
    }

    fun onQueryChange(value: String) {
        // Typing free text supersedes any earlier chip tap — clear the highlight so it doesn't
        // keep pointing at a genre that's no longer what's actually being searched.
        _uiState.value = _uiState.value.copy(query = value, selectedGenreTag = null)
        if (_uiState.value.mode == DiscoverSearchMode.GENRE) updateTagSuggestions(value)
        scheduleSearch()
    }

    /**
     * Looks up [prefix] against [tagRepository]'s local tag cache as the user types in the GENRE
     * tab, showing real matching tags (see [com.freqcast.ui.DiscoverStationsScreen]'s
     * `TagSuggestionsList`) instead of guessing. A plain local DB read (no network), so unlike
     * [scheduleSearch] this needs no debounce — each keystroke just cancels and replaces the
     * previous lookup.
     */
    private fun updateTagSuggestions(prefix: String) {
        suggestJob?.cancel()
        val trimmed = prefix.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(tagSuggestions = emptyList())
            return
        }
        suggestJob =
            viewModelScope.launch {
                val matches = tagRepository.searchByPrefix(trimmed)
                Log.d(TAG, "updateTagSuggestions: prefix=\"$trimmed\" matched ${matches.size} cached tags")
                _uiState.value = _uiState.value.copy(tagSuggestions = matches.map { it.tag })
            }
    }

    /** Called when the user taps a suggestion from [updateTagSuggestions]'s dropdown. */
    fun onTagSuggestionSelected(tag: String) {
        _uiState.value = _uiState.value.copy(query = tag)
        searchGenre(tag)
    }

    /**
     * Called when the user taps a GENRE tab quick-select chip ([com.freqcast.util.GenreCatalog]) —
     * see [com.freqcast.ui.DiscoverStationsScreen]'s `PopularGenresChips`, always visible below the
     * search field so a different genre can be picked at any time, not just before the first
     * search. Searches directly on [tag] (the chip's English `queryTag`) without routing through
     * [onQueryChange] — that would echo the English tag into the visible query field, which reads
     * as wrong under a chip labeled in the user's own language. Same "search without a visible
     * query" shape as [searchNearby]/the COUNTRY tab's flag picker. [selectedGenreTag] tracks which
     * chip to highlight, independent of [query] (which stays untouched here).
     */
    fun searchGenre(tag: String) {
        searchJob?.cancel()
        suggestJob?.cancel()
        _uiState.value = _uiState.value.copy(selectedGenreTag = tag, tagSuggestions = emptyList())
        searchJob = viewModelScope.launch { runSearch(tag) }
    }

    fun onModeChange(mode: DiscoverSearchMode) {
        if (mode == _uiState.value.mode) return
        searchJob?.cancel()
        suggestJob?.cancel()
        _uiState.value =
            _uiState.value.copy(
                mode = mode,
                // Each mode's query means something different (free text vs. a picked country
                // name) — carrying it over would silently search the new mode with a leftover
                // value the user never entered there.
                query = "",
                results = emptyList(),
                isSearching = false,
                hasSearched = false,
                errorRes = null,
                locationPermissionDenied = false,
                selectedGenreTag = null,
                tagSuggestions = emptyList(),
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
                    coroutineContext.ensureActive()
                    _uiState.value = _uiState.value.copy(results = results, isSearching = false, hasSearched = true)
                } catch (e: CancellationException) {
                    // Superseded by a newer search (searchJob?.cancel()), not a real failure.
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "searchNearby failed", e)
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
                DiscoverSearchMode.NEARBY -> return
            }
        try {
            val results = api.search(query, searchBy)
            // api.search()'s blocking OkHttp call isn't itself interruptible, so a cancellation
            // requested while it was in flight may not have surfaced as an exception yet by the
            // time it returns — check explicitly rather than let a stale, superseded search's
            // results overwrite whatever the newer search already put in _uiState.
            coroutineContext.ensureActive()
            _uiState.value = _uiState.value.copy(results = results, isSearching = false, hasSearched = true)
        } catch (e: CancellationException) {
            // scheduleSearch() cancels this job whenever a newer keystroke supersedes it — if that
            // lands while api.search() is already in flight, it surfaces here, not just as a
            // cancelled delay(); not a real failure, so it must not become discover_search_error.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search failed: query=\"$query\", searchBy=$searchBy", e)
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
        private const val TAG = "DiscoverStations"
        private const val SEARCH_DEBOUNCE_MS = 400L
        private const val NEARBY_RADIUS_METERS = 50_000
        private const val DEFAULT_BROWSE_LIMIT = 30

        /**
         * Comfortably below the real tag catalog's current ~11.9k size but well above the ~1000
         * rows a truncated/no-limit fetch would produce - see [syncTagsIfNeeded]'s doc.
         */
        private const val MIN_HEALTHY_TAG_COUNT = 2000

        // "en" is deliberately absent — ambiguous across US/GB/etc, so it falls through to the
        // system region instead of guessing. "zh-CN" needs no entry: its tag already carries "CN".
        private val LANGUAGE_TO_DEFAULT_REGION = mapOf("ru" to "RU", "es" to "ES")

        fun provideFactory(
            repository: RadioStationRepository,
            context: Context,
        ): ViewModelProvider.Factory =
            viewModelFactory { DiscoverStationsViewModel(repository, context.applicationContext) }
    }
}
