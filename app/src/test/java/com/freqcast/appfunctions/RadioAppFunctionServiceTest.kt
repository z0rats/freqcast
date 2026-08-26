package com.freqcast.appfunctions

import com.freqcast.data.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioAppFunctionServiceTest {
    private val jazz = station("Jazz FM", "https://example.com/jazz")
    private val rock = station("Rock FM", "https://example.com/rock")
    private val stations = listOf(jazz, rock)

    @Test
    fun `finds a station by exact name`() {
        assertEquals(jazz, findStationByName(stations, "Jazz FM"))
    }

    @Test
    fun `matches station names case-insensitively`() {
        assertEquals(rock, findStationByName(stations, "rock fm"))
        assertEquals(rock, findStationByName(stations, "ROCK FM"))
    }

    @Test
    fun `returns null when no station name matches`() {
        assertNull(findStationByName(stations, "Classical FM"))
    }

    @Test
    fun `returns null when there are no stations`() {
        assertNull(findStationByName(emptyList(), "Jazz FM"))
    }

    private fun station(
        name: String,
        url: String,
    ) = RadioStation(name = name, streamUrl = url)
}
