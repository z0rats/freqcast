package com.freqcast.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared `{name, streamUrl, customIcon, description, isHls, radioBrowserUuid}` JSON shape used by
 * both bulk and per-station backups. Deliberately excludes `sortOrder` (app-local list position,
 * not meaningful across devices/imports — imported stations are appended to the end of the target
 * list, same as any other new station) and, since removal, `isFavorite` (older backup files may
 * still have it; `RadioStationRepository.importStationsFromJson` simply doesn't read it anymore).
 * `description` was named `genre` before the column was renamed; older backup files still carry
 * that key, so `RadioStationRepository.importStationsFromJson` falls back to reading it too.
 */
object StationBackupJson {
    fun toJsonObject(station: RadioStation): JSONObject =
        JSONObject().apply {
            put("name", station.name)
            put("streamUrl", station.streamUrl)
            put("customIcon", station.customIcon ?: JSONObject.NULL)
            put("description", station.description ?: JSONObject.NULL)
            put("isHls", station.isHls)
            put("radioBrowserUuid", station.radioBrowserUuid ?: JSONObject.NULL)
        }

    fun toJsonArray(stations: List<RadioStation>): String {
        val array = JSONArray()
        stations.forEach { array.put(toJsonObject(it)) }
        // org.json escapes '/' as '\/' by default (legal JSON, but needlessly ugly for URLs);
        // unescaping is safe since '/' never appears in any other JSON escape sequence.
        return array.toString(2).replace("\\/", "/")
    }
}
