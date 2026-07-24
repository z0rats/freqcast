package com.freqcast.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "radio_stations",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["streamUrl"], unique = true),
    ],
)
data class RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null, // Emoji string or image file path
    // Manual position in the station list (ascending); getAllStations() orders by this. New
    // stations are appended (repository.insertStation assigns max(sortOrder)+1), and
    // repository.restoreStation (undoDelete) is the one path that preserves an explicit value
    // instead, so undoing a delete restores the station to its original position.
    val sortOrder: Int = 0,
    val description: String? = null,
    // Known-HLS hint from the Radio Browser directory's own `hls` flag, filled in for
    // Discover-added stations only; false for manual adds, which fall back to isHlsUrl()'s
    // URL heuristic in RadioPlaybackService.
    val isHls: Boolean = false,
    // The Radio Browser directory's stationuuid, filled in for Discover-added stations only;
    // null for manual adds. Lets RadioPlaybackService register a play as a "click" with the
    // directory (GET /json/url/{uuid}) to contribute to its popularity ranking.
    val radioBrowserUuid: String? = null,
)
