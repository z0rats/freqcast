package com.urlradiodroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioStationTest {
    @Test
    fun `test RadioStation data class equality`() {
        val station1 =
            RadioStation(
                id = 1L,
                name = "Test Station",
                streamUrl = "http://example.com/stream",
            )
        val station2 =
            RadioStation(
                id = 1L,
                name = "Test Station",
                streamUrl = "http://example.com/stream",
            )
        val station3 =
            RadioStation(
                id = 2L,
                name = "Test Station",
                streamUrl = "http://example.com/stream",
            )

        assertEquals(station1, station2)
        assertNotEquals(station1, station3)
    }

    @Test
    fun `test RadioStation with default id`() {
        val station =
            RadioStation(
                name = "New Station",
                streamUrl = "https://example.com/radio",
            )

        assertEquals(0L, station.id)
        assertEquals("New Station", station.name)
        assertEquals("https://example.com/radio", station.streamUrl)
    }

    @Test
    fun `test RadioStation properties`() {
        val station =
            RadioStation(
                id = 42L,
                name = "My Radio",
                streamUrl = "http://radio.example.com:8000/live",
            )

        assertEquals(42L, station.id)
        assertEquals("My Radio", station.name)
        assertEquals("http://radio.example.com:8000/live", station.streamUrl)
    }

    @Test
    fun `test RadioStation with null customIcon`() {
        val station = RadioStation(name = "No Icon", streamUrl = "http://example.com/noicon")
        assertNull(station.customIcon)
    }

    @Test
    fun `test RadioStation with customIcon`() {
        val station =
            RadioStation(
                name = "With Icon",
                streamUrl = "http://example.com/icon",
                customIcon = "🎵",
            )
        assertEquals("🎵", station.customIcon)
    }
}
