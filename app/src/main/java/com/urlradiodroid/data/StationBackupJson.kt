package com.urlradiodroid.data

import org.json.JSONArray
import org.json.JSONObject

/** Shared `{name, streamUrl, customIcon}` JSON shape used by both bulk and per-station backups. */
object StationBackupJson {
    fun toJsonObject(station: RadioStation): JSONObject =
        JSONObject().apply {
            put("name", station.name)
            put("streamUrl", station.streamUrl)
            put("customIcon", station.customIcon ?: JSONObject.NULL)
        }

    fun toJsonArray(stations: List<RadioStation>): String {
        val array = JSONArray()
        stations.forEach { array.put(toJsonObject(it)) }
        // org.json escapes '/' as '\/' by default (legal JSON, but needlessly ugly for URLs);
        // unescaping is safe since '/' never appears in any other JSON escape sequence.
        return array.toString(2).replace("\\/", "/")
    }
}
