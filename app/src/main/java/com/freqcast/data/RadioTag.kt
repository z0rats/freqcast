package com.freqcast.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single tag from the Radio Browser directory's full `/json/tags` catalog (~11k entries
 * worldwide), cached locally so the Discover GENRE tab's autocomplete can suggest real matches
 * offline/instantly instead of hitting the network on every keystroke. See [RadioTagDao] and
 * [RadioTagRepository].
 */
@Entity(tableName = "radio_tags")
data class RadioTag(
    @PrimaryKey
    val tag: String,
    val stationCount: Int,
)
