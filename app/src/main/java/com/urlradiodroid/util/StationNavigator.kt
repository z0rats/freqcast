package com.urlradiodroid.util

import com.urlradiodroid.data.RadioStation

/**
 * Pure next/prev station selection for the home screen widget's skip buttons, kept independent of
 * any Android framework type so it's plain-JUnit testable (see `AlarmScheduler.nextTriggerMillis`
 * for the same pattern elsewhere in this codebase). Operates on whatever ordering [stations] is
 * already in (the DB's `isFavorite DESC, id ASC`, same as the station list and the browse tree).
 */
object StationNavigator {
    /**
     * The station after [currentStreamUrl] in [stations], wrapping around to the first station
     * past the end. Falls back to the first station when [currentStreamUrl] isn't found (e.g.
     * nothing has ever played) or is null. Null only when [stations] is empty.
     */
    fun next(
        stations: List<RadioStation>,
        currentStreamUrl: String?,
    ): RadioStation? = adjacent(stations, currentStreamUrl, step = 1)

    /** The station before [currentStreamUrl] in [stations], wrapping around to the last station. */
    fun previous(
        stations: List<RadioStation>,
        currentStreamUrl: String?,
    ): RadioStation? = adjacent(stations, currentStreamUrl, step = -1)

    private fun adjacent(
        stations: List<RadioStation>,
        currentStreamUrl: String?,
        step: Int,
    ): RadioStation? {
        if (stations.isEmpty()) return null
        val currentIndex = stations.indexOfFirst { it.streamUrl == currentStreamUrl }
        if (currentIndex == -1) return stations.first()
        return stations[(currentIndex + step).mod(stations.size)]
    }
}
