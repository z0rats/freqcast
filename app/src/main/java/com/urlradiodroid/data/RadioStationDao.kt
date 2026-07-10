package com.urlradiodroid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface RadioStationDao {
    @Query("SELECT * FROM radio_stations ORDER BY isFavorite DESC, id ASC")
    suspend fun getAllStations(): List<RadioStation>

    @Query("SELECT * FROM radio_stations WHERE id = :id")
    suspend fun getStationById(id: Long): RadioStation?

    @Insert
    suspend fun insertStation(station: RadioStation): Long

    @Update
    suspend fun updateStation(station: RadioStation)

    @Query("DELETE FROM radio_stations WHERE id = :id")
    suspend fun deleteStation(id: Long)

    @Query("UPDATE radio_stations SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(
        id: Long,
        isFavorite: Boolean,
    )

    @Query("SELECT * FROM radio_stations WHERE name = :name AND id != :excludeId LIMIT 1")
    suspend fun findStationByName(
        name: String,
        excludeId: Long = 0,
    ): RadioStation?

    @Query("SELECT * FROM radio_stations WHERE streamUrl = :url AND id != :excludeId LIMIT 1")
    suspend fun findStationByUrl(
        url: String,
        excludeId: Long = 0,
    ): RadioStation?
}
