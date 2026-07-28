package com.freqcast.util

import java.util.Locale

/** A country entry for the Discover "Country" flag picker, named as radio-browser.info expects. */
data class Country(
    val flag: String,
    val englishName: String,
)

/** A small curated set of countries for the Discover "Country" flag picker, in a fixed display order. */
object CountryCatalog {
    private val ISO_CODES = listOf("RU", "BY", "US", "ES", "DE", "FR", "IT", "GB", "PL", "CN")

    val countries: List<Country> by lazy {
        ISO_CODES.mapNotNull { code ->
            val flag = CountryFlagEmoji.from(code) ?: return@mapNotNull null
            val englishName =
                Locale
                    .Builder()
                    .setRegion(code)
                    .build()
                    .getDisplayCountry(Locale.ENGLISH)
            Country(flag, englishName)
        }
    }
}
