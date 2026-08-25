package com.freqcast.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun `formats minutes and zero-padded seconds`() {
        assertEquals("−2:15", formatOffsetFromLive(135_000L))
    }

    @Test
    fun `zero formats as zero minutes and seconds`() {
        assertEquals("−0:00", formatOffsetFromLive(0L))
    }

    @Test
    fun `pads single-digit seconds with a leading zero`() {
        assertEquals("−0:05", formatOffsetFromLive(5_000L))
    }

    @Test
    fun `rounds down to the nearest whole second`() {
        assertEquals("−0:01", formatOffsetFromLive(1_999L))
    }

    @Test
    fun `an exact minute boundary rolls over to the next minute with zero seconds`() {
        assertEquals("−1:00", formatOffsetFromLive(60_000L))
    }

    @Test
    fun `a negative duration is coerced to zero instead of going negative`() {
        assertEquals("−0:00", formatOffsetFromLive(-5_000L))
    }
}
