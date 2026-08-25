package com.freqcast.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryCatalogTest {
    @Test
    fun `englishNameForRegion resolves a valid ISO region code case-insensitively`() {
        assertEquals("Germany", CountryCatalog.englishNameForRegion("DE"))
        assertEquals("Germany", CountryCatalog.englishNameForRegion("de"))
    }

    @Test
    fun `englishNameForRegion returns null for a code that isn't a real ISO region`() {
        assertNull(CountryCatalog.englishNameForRegion("ZZ"))
        assertNull(CountryCatalog.englishNameForRegion(""))
    }

    @Test
    fun `countries exposes the curated flag-picker list in its fixed display order`() {
        val names = CountryCatalog.countries.map { it.englishName }

        assertEquals(
            listOf(
                "Russia",
                "Belarus",
                "United States",
                "Spain",
                "Germany",
                "France",
                "Italy",
                "United Kingdom",
                "Poland",
                "China",
            ),
            names,
        )
        assertEquals(
            CountryCatalog.countries.size,
            CountryCatalog.countries
                .map { it.flag }
                .distinct()
                .size,
        )
    }
}
