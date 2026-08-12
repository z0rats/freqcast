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
            val englishName = englishNameForRegion(code)
            Country(flag, requireNotNull(englishName))
        }
    }

    private val VALID_REGION_CODES: Set<String> by lazy { Locale.getISOCountries().toSet() }

    /**
     * English display name for any valid ISO 3166-1 alpha-2 region code — not limited to the
     * curated [countries] picker list. Used to resolve an arbitrary device region (e.g. from
     * [android.content.res.Configuration]) to the name radio-browser.info's `country` filter
     * expects, without expanding the flag-picker chip row itself.
     */
    fun englishNameForRegion(regionCode: String): String? {
        val code = regionCode.uppercase(Locale.ROOT)
        if (code !in VALID_REGION_CODES) return null
        return Locale
            .Builder()
            .setRegion(code)
            .build()
            .getDisplayCountry(Locale.ENGLISH)
    }
}
