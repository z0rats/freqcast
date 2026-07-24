package com.freqcast.ui.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository

/**
 * Owns the Android Auto / Assistant browse tree's contents: a flat list of stations under one
 * root, exposed as [MediaItem]s. Caches the [RadioStation] backing each item from the most recent
 * [loadStations] call so a tap can be resolved to a full station without a DB round trip - same
 * idea as [TimeshiftController]/[SleepTimerController], moved out of
 * [com.freqcast.ui.RadioPlaybackService] to keep the browse-tree bookkeeping in one place.
 */
class RadioBrowseTree(
    private val repository: RadioStationRepository,
    private val appName: String,
) {
    private var cachedStations: List<RadioStation> = emptyList()

    val rootItem: MediaItem =
        MediaItem
            .Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(appName)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build(),
            ).build()

    fun isRoot(mediaId: String): Boolean = mediaId == ROOT_ID

    /** Loads the current station list, caches it for [findStation]/[mediaItemFor], and returns it as browsable items. */
    suspend fun loadStations(): List<MediaItem> {
        val stations = repository.getAllStations()
        cachedStations = stations
        return stations.map { it.toBrowsableMediaItem() }
    }

    /** The cached station backing [mediaId] (a station's stream URL), from the most recent [loadStations] call. */
    fun findStation(mediaId: String): RadioStation? = cachedStations.find { it.streamUrl == mediaId }

    fun mediaItemFor(mediaId: String): MediaItem? = findStation(mediaId)?.toBrowsableMediaItem()

    private fun RadioStation.toBrowsableMediaItem(): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(streamUrl)
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(name)
                    .setArtist(appName)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build(),
            ).build()

    companion object {
        const val ROOT_ID = "root"
    }
}
