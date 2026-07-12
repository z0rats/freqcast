package com.urlradiodroid.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StationBackupJsonTest {
    @Test
    fun `toJsonObject serializes name, streamUrl, customIcon, isFavorite and genre`() {
        val station =
            RadioStation(
                name = "Rock FM",
                streamUrl = "http://example.com/rock",
                customIcon = "🎸",
                isFavorite = true,
                genre = "rock",
            )

        val obj = StationBackupJson.toJsonObject(station)

        assertEquals("Rock FM", obj.getString("name"))
        assertEquals("http://example.com/rock", obj.getString("streamUrl"))
        assertEquals("🎸", obj.getString("customIcon"))
        assertTrue(obj.getBoolean("isFavorite"))
        assertEquals("rock", obj.getString("genre"))
    }

    @Test
    fun `toJsonObject serializes a null customIcon and null genre as JSON null`() {
        val station = RadioStation(name = "Jazz Radio", streamUrl = "http://example.com/jazz")

        val obj = StationBackupJson.toJsonObject(station)

        assertTrue(obj.isNull("customIcon"))
        assertTrue(obj.isNull("genre"))
    }

    @Test
    fun `toJsonArray with a single station produces a one-element array`() {
        val station = RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock")

        val array = JSONArray(StationBackupJson.toJsonArray(listOf(station)))

        assertEquals(1, array.length())
        assertEquals("Rock FM", array.getJSONObject(0).getString("name"))
    }

    @Test
    fun `toJsonArray with no stations returns an empty array`() {
        assertEquals("[]", StationBackupJson.toJsonArray(emptyList()))
    }

    @Test
    fun `toJsonArray does not escape forward slashes in streamUrl`() {
        val station = RadioStation(name = "Rock FM", streamUrl = "https://example.com/rock/stream.mp3")

        val json = StationBackupJson.toJsonArray(listOf(station))

        assertFalse(json.contains("\\/"))
        assertTrue(json.contains("https://example.com/rock/stream.mp3"))
        // Still round-trips correctly through the JSON parser.
        val array = JSONArray(json)
        assertEquals("https://example.com/rock/stream.mp3", array.getJSONObject(0).getString("streamUrl"))
    }

    @Test
    fun `toJsonArray preserves station order`() {
        val stations =
            listOf(
                RadioStation(name = "A", streamUrl = "http://example.com/a"),
                RadioStation(name = "B", streamUrl = "http://example.com/b"),
            )

        val array = JSONArray(StationBackupJson.toJsonArray(stations))

        assertEquals("A", array.getJSONObject(0).getString("name"))
        assertEquals("B", array.getJSONObject(1).getString("name"))
    }
}
