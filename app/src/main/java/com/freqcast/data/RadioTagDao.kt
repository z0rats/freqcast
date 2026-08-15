package com.freqcast.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface RadioTagDao {
    @Query("SELECT COUNT(*) FROM radio_tags")
    suspend fun count(): Int

    @Query("SELECT * FROM radio_tags WHERE tag LIKE :prefix || '%' ORDER BY stationCount DESC LIMIT :limit")
    suspend fun searchByPrefix(
        prefix: String,
        limit: Int,
    ): List<RadioTag>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<RadioTag>)

    @Query("DELETE FROM radio_tags")
    suspend fun deleteAll()

    /** Full resync: drops whatever was cached before inserting [tags], so a stale tag never lingers. */
    @Transaction
    suspend fun replaceAll(tags: List<RadioTag>) {
        deleteAll()
        insertAll(tags)
    }
}
