package com.freqcast.util

import com.freqcast.R

/**
 * A genre entry for the Discover "Genre" tab's default-browse chips: [queryTag] is what's sent to
 * radio-browser.info's `tag` filter (an English word, since that's the convention real-world
 * station tags overwhelmingly use regardless of the station's own language), [labelRes] is the
 * localized chip label shown to the user.
 */
data class Genre(
    val queryTag: String,
    val labelRes: Int,
)

/**
 * A small curated, translated set of common genres for the Discover GENRE tab's default-browse
 * chips — deliberately not radio-browser.info's `/json/tags` (real top tags by station count):
 * that endpoint returns whatever free-text stations tagged themselves with, mixing languages and
 * scripts unpredictably (station operators worldwide tag in their own language), which reads as
 * "random" clutter rather than a clean single-language chip row. Same curated-over-dynamic
 * tradeoff as [CountryCatalog]'s flag picker.
 */
object GenreCatalog {
    val genres =
        listOf(
            Genre("pop", R.string.discover_genre_pop),
            Genre("rock", R.string.discover_genre_rock),
            Genre("jazz", R.string.discover_genre_jazz),
            Genre("news", R.string.discover_genre_news),
            Genre("talk", R.string.discover_genre_talk),
            Genre("classical", R.string.discover_genre_classical),
            Genre("electronic", R.string.discover_genre_electronic),
            Genre("dance", R.string.discover_genre_dance),
            Genre("hip hop", R.string.discover_genre_hip_hop),
            Genre("oldies", R.string.discover_genre_oldies),
        )
}
