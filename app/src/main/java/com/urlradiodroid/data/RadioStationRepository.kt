package com.urlradiodroid.data

import org.json.JSONArray
import org.json.JSONException

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

    suspend fun insertStation(station: RadioStation): Long = dao.insertStation(station)

    suspend fun updateStation(station: RadioStation) = dao.updateStation(station)

    suspend fun deleteStation(id: Long) = dao.deleteStation(id)

    suspend fun setFavorite(
        id: Long,
        isFavorite: Boolean,
    ) = dao.setFavorite(id, isFavorite)

    suspend fun isNameTaken(
        name: String,
        excludeId: Long = 0,
    ): Boolean = dao.findStationByName(name, excludeId) != null

    suspend fun isUrlTaken(
        url: String,
        excludeId: Long = 0,
    ): Boolean = dao.findStationByUrl(url, excludeId) != null

    /** Serializes all stations to a JSON array of `{name, streamUrl, customIcon}` objects. */
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
            val icon =
                if (!obj.has("customIcon") || obj.isNull("customIcon")) {
                    null
                } else {
                    obj.optString("customIcon").ifBlank { null }
                }
            val isFavorite = obj.optBoolean("isFavorite", false)
            insertStation(RadioStation(name = name, streamUrl = url, customIcon = icon, isFavorite = isFavorite))
            imported++
        }
        return ImportResult(imported, skipped, failed)
    }

    companion object {
        fun create(context: android.content.Context): RadioStationRepository =
            RadioStationRepository(AppDatabase.getDatabase(context).radioStationDao())
    }
}
