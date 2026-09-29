package com.freqcast.data

/** What [RadioStationRepository.insertStationIfAbsent] does when [RadioStation.name] collides with a different existing station. */
enum class NameCollisionPolicy {
    /** Append " (2)", " (3)", ... via [RadioStationRepository.uniqueName] until it no longer collides. */
    RENAME,

    /** Don't insert at all. */
    SKIP,
}

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

    /**
     * Inserts [station] unless a station with the same [RadioStation.streamUrl] already exists -
     * the url is what makes it *the same station*, so a url collision always means "nothing to
     * do" regardless of [onNameCollision]. A name collision with a *different* station is
     * resolved per [onNameCollision]. Returns the inserted row's id, or `null` if nothing was
     * inserted.
     *
     * The one seam every "add a station without interactive per-field validation" flow goes
     * through - [RadioBrowserStationInstaller], curated-pack seeding, and curated-pack restore -
     * so they can't drift into different collision behavior again. [com.freqcast.ui.AddStationViewModel]'s
     * own save flow is deliberately not routed through this: it surfaces name/url collisions as
     * distinct field errors to the user instead of silently renaming or skipping.
     */
    suspend fun insertStationIfAbsent(
        station: RadioStation,
        onNameCollision: NameCollisionPolicy = NameCollisionPolicy.SKIP,
    ): Long? {
        if (isUrlTaken(station.streamUrl)) return null
        val name =
            when (onNameCollision) {
                NameCollisionPolicy.RENAME -> uniqueName(station.name)
                NameCollisionPolicy.SKIP -> if (isNameTaken(station.name)) return null else station.name
            }
        return insertStation(station.copy(name = name))
    }

    companion object {
        fun create(context: android.content.Context): RadioStationRepository =
            RadioStationRepository(AppDatabase.getDatabase(context).radioStationDao())
    }
}
