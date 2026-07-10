package com.urlradiodroid.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "radio_stations",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["streamUrl"], unique = true),
    ],
)
data class RadioStation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val streamUrl: String,
    val customIcon: String? = null, // Emoji string or image file path
)
