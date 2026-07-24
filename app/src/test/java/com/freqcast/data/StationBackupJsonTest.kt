package com.freqcast.data

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
    fun `toJsonObject serializes name, streamUrl, customIcon, description, isHls and radioBrowserUuid`() {
        val station =
            RadioStation(
                name = "Rock FM",
                streamUrl = "http://example.com/rock",
                customIcon = "🎸",
                description = "rock",
                isHls = true,
                radioBrowserUuid = "uuid-1",
            )

        val obj = StationBackupJson.toJsonObject(station)

        assertEquals("Rock FM", obj.getString("name"))
        assertEquals("http://example.com/rock", obj.getString("streamUrl"))
        assertEquals("🎸", obj.getString("customIcon"))
        assertEquals("rock", obj.getString("description"))
        assertTrue(obj.getBoolean("isHls"))
        assertEquals("uuid-1", obj.getString("radioBrowserUuid"))
    }

    @Test
    fun `toJsonObject serializes a null customIcon, description and radioBrowserUuid as JSON null`() {
        val station = RadioStation(name = "Jazz Radio", streamUrl = "http://example.com/jazz")

        val obj = StationBackupJson.toJsonObject(station)

        assertTrue(obj.isNull("customIcon"))
        assertTrue(obj.isNull("description"))
        assertTrue(obj.isNull("radioBrowserUuid"))
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
