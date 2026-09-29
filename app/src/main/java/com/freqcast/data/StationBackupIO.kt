package com.freqcast.data

import android.content.Context
import com.freqcast.util.IconStorage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/** Null when [key] is absent/JSON-null, or when the string it holds is blank. */
private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

/** Outcome of [StationBackupIO.importJson]/[StationBackupIO.importPlaylist]/[StationBackupIO.import]. */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
)

/**
 * Export/import for the whole station library — a JSON backup ([StationBackupJson]'s shape)
 * or an OPML/M3U/PLS playlist ([PlaylistImport]). Split out of
 * [RadioStationRepository] (which still owns every actual write, via [insertStation]) so that
 * class stays a plain station-table gateway; this is the one place for "what does a station
 * backup file look like and how do we read one back."
 */
class StationBackupIO(
    private val repository: RadioStationRepository,
) {
    /**
     * Serializes all stations to a JSON array of `{name, streamUrl, customIcon, description, isHls,
     * radioBrowserUuid}` objects, or `null` if there are no saved stations to export.
     */
    suspend fun export(): String? {
        val stations = repository.getAllStations()
        if (stations.isEmpty()) return null
        return StationBackupJson.toJsonArray(stations)
    }

    /**
     * Imports stations from a JSON array produced by [export]. Entries whose name or URL already
     * exists are skipped rather than overwritten; entries missing a name or URL are counted as
     * failed. Throws [IllegalArgumentException] if [json] isn't a JSON array. [context] is needed
     * to persist an entry's `iconData` payload (if present) as a new local icon file via
     * [IconStorage] — see [resolveImportedIcon].
     */
    suspend fun importJson(
        context: Context,
        json: String,
    ): ImportResult {
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
            if (repository.isNameTaken(name) || repository.isUrlTaken(url)) {
                // Cheap pre-check before the icon-decoding I/O below, so a duplicate entry
                // doesn't leave an orphaned icon file behind - the actual insert still goes
                // through insertStationIfAbsent, which is the source of truth for the skip.
                skipped++
                continue
            }
            val icon = resolveImportedIcon(context, obj)
            // "genre" was this field's name before the column was renamed to "description";
            // older backup files still carry it under that key.
            val description = obj.optNullableString("description") ?: obj.optNullableString("genre")
            val isHls = obj.optBoolean("isHls", false)
            val radioBrowserUuid = obj.optNullableString("radioBrowserUuid")
            val inserted =
                repository.insertStationIfAbsent(
                    RadioStation(
                        name = name,
                        streamUrl = url,
                        customIcon = icon,
                        description = description,
                        isHls = isHls,
                        radioBrowserUuid = radioBrowserUuid,
                    ),
                )
            if (inserted != null) imported++ else skipped++
        }
        return ImportResult(imported, skipped, failed)
    }

    /**
     * Resolves an imported entry's icon: if a base64 `iconData` payload is present (added by
     * [StationBackupJson] for a locally stored icon image so it survives a move to another device),
     * decodes it and persists it as a new local icon file via [IconStorage] — the entry's
     * `customIcon` string came from the exporting device and won't resolve here. Falls back to that
     * `customIcon` string as-is (an emoji, or a pre-`iconData` backup's now-unresolvable path) when
     * no `iconData` payload is present.
     */
    private fun resolveImportedIcon(
        context: Context,
        obj: JSONObject,
    ): String? {
        val iconData = obj.optNullableString("iconData") ?: return obj.optNullableString("customIcon")
        val bytes = runCatching { Base64.getDecoder().decode(iconData) }.getOrNull() ?: return null
        return IconStorage.saveImageBytes(context, bytes)
    }

    /**
     * Imports stations from an OPML, M3U/M3U8, or PLS playlist file (format sniffed by
     * [PlaylistImport]). Only `name`/`streamUrl` are known from these formats, so every other
     * field stays at its entity default. Same skip-on-duplicate/failed-on-missing-field semantics
     * as [importJson]. Throws [IllegalArgumentException] if the format isn't recognized.
     */
    suspend fun importPlaylist(content: String): ImportResult {
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
            val inserted = repository.insertStationIfAbsent(RadioStation(name = name, streamUrl = url))
            if (inserted != null) imported++ else skipped++
        }
        return ImportResult(imported, skipped, failed)
    }

    /**
     * Single import entry point for [SettingsScreen]/`SettingsViewModel`: sniffs whether [content]
     * is a JSON stations backup ([importJson]) or an OPML/M3U/PLS playlist ([importPlaylist]) and
     * dispatches accordingly.
     */
    suspend fun import(
        context: Context,
        content: String,
    ): ImportResult =
        if (content.trimStart().startsWith("[")) {
            importJson(context, content)
        } else {
            importPlaylist(content)
        }
}
