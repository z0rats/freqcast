package com.freqcast.data

import com.freqcast.util.IconStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Shared `{name, streamUrl, customIcon, description, isHls, radioBrowserUuid, iconData}` JSON shape
 * used by both bulk and per-station backups. Deliberately excludes `sortOrder` (app-local list
 * position, not meaningful across devices/imports — imported stations are appended to the end of
 * the target list, same as any other new station) and, since removal, `isFavorite` (older backup
 * files may still have it; `RadioStationRepository.importStationsFromJson` simply doesn't read it
 * anymore). `description` was named `genre` before the column was renamed; older backup files still
 * carry that key, so `RadioStationRepository.importStationsFromJson` falls back to reading it too.
 * `iconData` is a base64-encoded copy of a locally stored icon image's bytes (see [encodeIconData]);
 * it's absent for emoji icons, stations with no icon, or unreadable icon files.
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
            encodeIconData(station.customIcon)?.let { put("iconData", it) }
        }

    /**
     * Base64-encodes a locally stored icon image's bytes so the backup carries the actual image,
     * not just [RadioStation.customIcon]'s device-local file path — which is meaningless once moved
     * to another device or after a reinstall. Null for an emoji icon, no icon, or an unreadable
     * file; those fall back to `customIcon` alone, same as before this field existed.
     */
    private fun encodeIconData(customIcon: String?): String? {
        if (customIcon == null || !IconStorage.isImagePath(customIcon)) return null
        val bytes = runCatching { File(customIcon).readBytes() }.getOrNull() ?: return null
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun toJsonArray(stations: List<RadioStation>): String {
        val array = JSONArray()
        stations.forEach { array.put(toJsonObject(it)) }
        // org.json escapes '/' as '\/' by default (legal JSON, but needlessly ugly for URLs);
        // unescaping is safe since '/' never appears in any other JSON escape sequence.
        return array.toString(2).replace("\\/", "/")
    }
}
