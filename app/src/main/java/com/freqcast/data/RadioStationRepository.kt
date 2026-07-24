package com.freqcast.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Null when [key] is absent/JSON-null, or when the string it holds is blank. */
private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

/** Outcome of [RadioStationRepository.importStationsFromJson]. */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
)

class RadioStationRepository(
    private val dao: RadioStationDao,
) {
    suspend fun getAllStations(): List<RadioStation> = dao.getAllStations()

    suspend fun getStationById(id: Long): RadioStation? = dao.getStationById(id)

    /** Inserts a new station, appended to the end of the manually-ordered list. */
    suspend fun insertStation(station: RadioStation): Long {
        val nextOrder = dao.getMaxSortOrder() + 1
        return dao.insertStation(station.copy(sortOrder = nextOrder))
    }

    /** Re-inserts [station] as-is, preserving its own `sortOrder` — used by undoDelete to restore position. */
    suspend fun restoreStation(station: RadioStation): Long = dao.insertStation(station)

    suspend fun updateStation(station: RadioStation) = dao.updateStation(station)

    suspend fun deleteStation(id: Long) = dao.deleteStation(id)

    /** Persists a new manual order — [orderedIds] is the full station list's ids in their new order. */
    suspend fun updateSortOrder(orderedIds: List<Long>) = dao.updateSortOrder(orderedIds)

    suspend fun isNameTaken(
        name: String,
        excludeId: Long = 0,
    ): Boolean = dao.findStationByName(name, excludeId) != null

    suspend fun isUrlTaken(
        url: String,
        excludeId: Long = 0,
    ): Boolean = dao.findStationByUrl(url, excludeId) != null

    suspend fun getStationByUrl(url: String): RadioStation? = dao.findStationByUrl(url)

    /** [base], or "[base] (2)", "[base] (3)", ... - whichever is the first not already taken by another station. */
    suspend fun uniqueName(
        base: String,
        excludeId: Long = 0,
    ): String {
        var candidate = base
        var suffix = 2
        while (isNameTaken(candidate, excludeId)) {
            candidate = "$base ($suffix)"
            suffix++
        }
        return candidate
    }

    /** Serializes all stations to a JSON array of `{name, streamUrl, customIcon, description, isHls, radioBrowserUuid}` objects. */
    suspend fun exportStationsToJson(): String = StationBackupJson.toJsonArray(dao.getAllStations())

    /**
     * Imports stations from a JSON array produced by [exportStationsToJson]. Entries whose
     * name or URL already exists are skipped rather than overwritten; entries missing a name
     * or URL are counted as failed. Throws [IllegalArgumentException] if [json] isn't a JSON array.
     */
    suspend fun importStationsFromJson(json: String): ImportResult {
        val array =
            try {
                JSONArray(json)
            } catch (e: JSONException) {
                throw IllegalArgumentException("Not a valid stations backup file", e)
            }

        var imported = 0
        var skipped = 0
        var failed = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            val name = obj?.optString("name")?.trim().orEmpty()
            val url = obj?.optString("streamUrl")?.trim().orEmpty()
            if (obj == null || name.isEmpty() || url.isEmpty()) {
                failed++
                continue
            }
            if (isNameTaken(name) || isUrlTaken(url)) {
                skipped++
                continue
            }
            val icon = obj.optNullableString("customIcon")
            // "genre" was this field's name before the column was renamed to "description";
            // older backup files still carry it under that key.
            val description = obj.optNullableString("description") ?: obj.optNullableString("genre")
            val isHls = obj.optBoolean("isHls", false)
            val radioBrowserUuid = obj.optNullableString("radioBrowserUuid")
            insertStation(
                RadioStation(
                    name = name,
                    streamUrl = url,
                    customIcon = icon,
                    description = description,
                    isHls = isHls,
                    radioBrowserUuid = radioBrowserUuid,
                ),
            )
            imported++
        }
        return ImportResult(imported, skipped, failed)
    }

    /**
     * Imports stations from an OPML, M3U/M3U8, or PLS playlist file (format sniffed by
     * [PlaylistImport]). Only `name`/`streamUrl` are known from these formats, so every other
     * field stays at its entity default. Same skip-on-duplicate/failed-on-missing-field semantics
     * as [importStationsFromJson]. Throws [IllegalArgumentException] if the format isn't recognized.
     */
    suspend fun importStationsFromPlaylist(content: String): ImportResult {
        val entries = PlaylistImport.parse(content)
        var imported = 0
        var skipped = 0
        var failed = 0
        for (entry in entries) {
            val name = entry.name.trim()
            val url = entry.streamUrl.trim()
            if (name.isEmpty() || url.isEmpty()) {
                failed++
                continue
            }
            if (isNameTaken(name) || isUrlTaken(url)) {
                skipped++
                continue
            }
            insertStation(RadioStation(name = name, streamUrl = url))
            imported++
        }
        return ImportResult(imported, skipped, failed)
    }

    /**
     * Single import entry point for [SettingsScreen]/`SettingsViewModel`: sniffs whether [content]
     * is a JSON stations backup ([importStationsFromJson]) or an OPML/M3U/PLS playlist
     * ([importStationsFromPlaylist]) and dispatches accordingly.
     */
    suspend fun importStations(content: String): ImportResult =
        if (content.trimStart().startsWith("[")) {
            importStationsFromJson(content)
        } else {
            importStationsFromPlaylist(content)
        }

    companion object {
        fun create(context: android.content.Context): RadioStationRepository =
            RadioStationRepository(AppDatabase.getDatabase(context).radioStationDao())
    }
}
