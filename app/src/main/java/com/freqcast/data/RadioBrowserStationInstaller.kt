package com.freqcast.data

import android.content.Context
import com.freqcast.util.IconStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saves a [RadioBrowserStation] directory search result as a local [RadioStation] - shared by
 * [com.freqcast.ui.DiscoverStationsViewModel] ("Найти станцию") and
 * [com.freqcast.ui.MainViewModel] (the main screen's search-catalog fallback), so the two
 * "add from the directory" flows can't silently drift apart.
 */
class RadioBrowserStationInstaller(
    private val repository: RadioStationRepository,
    private val api: RadioBrowserApi,
) {
    /**
     * Inserts [station] unless its url is already saved locally - returns `true` once it's saved
     * either way, `false` only on the rare unique-constraint race with the [repository.isUrlTaken]
     * check right above it (defense-in-depth on top of [com.freqcast.data.AppDatabase]'s own
     * indices); callers leave the station unmarked on `false` so the user can retry.
     *
     * The station is visible locally (with its auto-generated emoji icon) as soon as this
     * returns - a non-blank [RadioBrowserStation.favicon] is downloaded fire-and-forget in [scope]
     * afterwards and backfills `customIcon` once it lands, never blocking the insert on a network
     * round-trip.
     */
    suspend fun install(
        scope: CoroutineScope,
        appContext: Context,
        station: RadioBrowserStation,
    ): Boolean {
        if (repository.isUrlTaken(station.url)) return true
        val stationId =
            try {
                repository.insertStation(
                    RadioStation(
                        name = repository.uniqueName(station.name),
                        streamUrl = station.url,
                        description = station.tags.takeIf { it.isNotBlank() },
                        isHls = station.hls,
                        radioBrowserUuid = station.uuid.takeIf { it.isNotBlank() },
                    ),
                )
            } catch (e: Exception) {
                return false
            }
        station.favicon.takeIf { it.isNotBlank() }?.let { faviconUrl ->
            scope.launch { downloadAndSetFavicon(appContext, stationId, faviconUrl) }
        }
        return true
    }

    private suspend fun downloadAndSetFavicon(
        appContext: Context,
        stationId: Long,
        faviconUrl: String,
    ) {
        val bytes = api.downloadFavicon(faviconUrl) ?: return
        val path = withContext(Dispatchers.IO) { IconStorage.saveImageBytes(appContext, bytes) } ?: return
        repository.getStationById(stationId)?.let { current ->
            repository.updateStation(current.copy(customIcon = path))
        }
    }
}
