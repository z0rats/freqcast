package com.urlradiodroid.util

import com.urlradiodroid.data.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationNavigatorTest {
    private val jazz = station("Jazz FM", "https://example.com/jazz")
    private val rock = station("Rock FM", "https://example.com/rock")
    private val classical = station("Classical FM", "https://example.com/classical")
    private val stations = listOf(jazz, rock, classical)

    @Test
    fun `next returns the following station in list order`() {
        assertEquals(rock, StationNavigator.next(stations, jazz.streamUrl))
        assertEquals(classical, StationNavigator.next(stations, rock.streamUrl))
    }

    @Test
    fun `next wraps around from the last station back to the first`() {
        assertEquals(jazz, StationNavigator.next(stations, classical.streamUrl))
    }

    @Test
    fun `previous returns the preceding station in list order`() {
        assertEquals(jazz, StationNavigator.previous(stations, rock.streamUrl))
        assertEquals(rock, StationNavigator.previous(stations, classical.streamUrl))
    }

    @Test
    fun `previous wraps around from the first station back to the last`() {
        assertEquals(classical, StationNavigator.previous(stations, jazz.streamUrl))
    }

    @Test
    fun `falls back to the first station when nothing is currently playing`() {
        assertEquals(jazz, StationNavigator.next(stations, currentStreamUrl = null))
        assertEquals(jazz, StationNavigator.previous(stations, currentStreamUrl = null))
    }

    @Test
    fun `falls back to the first station when the current url is unknown, e g the station was deleted`() {
        assertEquals(jazz, StationNavigator.next(stations, "https://example.com/deleted"))
    }

    @Test
    fun `returns null when there are no stations`() {
        assertNull(StationNavigator.next(emptyList(), jazz.streamUrl))
        assertNull(StationNavigator.previous(emptyList(), currentStreamUrl = null))
    }

    @Test
    fun `single station wraps to itself`() {
        val single = listOf(jazz)
        assertEquals(jazz, StationNavigator.next(single, jazz.streamUrl))
        assertEquals(jazz, StationNavigator.previous(single, jazz.streamUrl))
    }

    private fun station(
        name: String,
        url: String,
    ) = RadioStation(name = name, streamUrl = url)
}
