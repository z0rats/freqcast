package com.freqcast.data

import android.content.Context
import com.freqcast.R
import com.freqcast.util.IconStorage

/**
 * The hardcoded "Developer's Picks" pack, seeded once into a fresh install's station list (see
 * [RadioStationRepository.insertStation] callers in `MainViewModel.seedCuratedStationsIfNeeded`).
 * Every entry gets [RadioStation.isCurated] = true, driving the "Curated" pill in `StationItem`.
 * Not sourced from Radio Browser, so [RadioStation.radioBrowserUuid] stays null; [RadioStation.isHls]
 * is set per-entry since HKCR's stream is HLS while the rest are plain progressive MP3.
 */
object CuratedStations {
    val pack: List<RadioStation> =
        listOf(
            RadioStation(
                name = "Радио Зимы не будет",
                // AzuraCast-hosted (server.radioznb.ru), shortcode "radioznb-live".
                streamUrl = "https://server.radioznb.ru/listen/radioznb-live/radio.mp3",
                customIcon = "❄️",
                description = "Independent Radio",
                isCurated = true,
            ),
            RadioStation(
                name = "KURS Radio",
                streamUrl = "https://listen9.myradio24.com/kursradio",
                customIcon = "🎙️",
                description = "Independent Radio · Moscow",
                isCurated = true,
            ),
            RadioStation(
                name = "HKCR",
                streamUrl = "https://stream-test.hkcr.live/hls/main.m3u8",
                customIcon = "🇭🇰",
                description = "Underground Music · Hong Kong",
                isHls = true,
                isCurated = true,
            ),
            RadioStation(
                name = "SURPRISE.FM",
                // surprise.fm's own primary domain (radio.surprise.fm) wasn't reachable for
                // verification from this environment (likely blocks cloud/datacenter egress IPs,
                // not a dead stream) - using its own configured backup_stream_url host instead,
                // confirmed live: myradio24-hosted mirror of the same station.
                streamUrl = "https://listen9.myradio24.com/surprise",
                customIcon = "🎁",
                description = "Radio, Shows & Podcasts",
                isCurated = true,
            ),
        )

    /**
     * Each pack entry's real site icon, bundled into the APK under `res/raw` (downloaded once at
     * development time, not fetched over the network at seed time - keeps first-launch seeding
     * fully offline and deterministic). Keyed by [RadioStation.name].
     */
    private val iconRes: Map<String, Int> =
        mapOf(
            "Радио Зимы не будет" to R.raw.curated_znb,
            "KURS Radio" to R.raw.curated_kurs,
            "HKCR" to R.raw.curated_hkcr,
            "SURPRISE.FM" to R.raw.curated_surprise,
        )

    /**
     * Copies [station]'s bundled `res/raw` icon through [IconStorage] into a real local icon file,
     * the same way a Discover-added station's downloaded favicon is stored - so from every other
     * part of the app (rendering, export, widget, Android Auto) a curated station's icon looks
     * exactly like any other locally-stored image icon, no special-casing needed. Falls back to
     * [station]'s existing emoji `customIcon` if the resource can't be read or decoded.
     */
    fun withResolvedIcon(
        context: Context,
        station: RadioStation,
    ): RadioStation {
        val resId = iconRes[station.name] ?: return station
        val bytes =
            runCatching { context.resources.openRawResource(resId).use { it.readBytes() } }
                .getOrNull() ?: return station
        val path = IconStorage.saveImageBytes(context, bytes) ?: return station
        return station.copy(customIcon = path)
    }
}
