package com.freqcast.data

import android.util.Log
import com.freqcast.util.APP_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A station returned by the [RadioBrowserApi] directory search, not yet saved locally. */
data class RadioBrowserStation(
    val uuid: String,
    val name: String,
    val url: String,
    val country: String,
    val tags: String,
    val bitrate: Int,
    val hls: Boolean = false,
    val countryCode: String = "",
    val codec: String = "",
    val votes: Int = 0,
    val homepage: String = "",
    val favicon: String = "",
    val distanceKm: Double? = null,
    val sslError: Boolean = false,
)

/**
 * Minimal client for the [Radio Browser API](https://api.radio-browser.info/) public station
 * directory, used for the "discover stations" search. `all.api.radio-browser.info` round-robins
 * across every currently available community-run mirror, so a plain HTTPS request to that host
 * is enough — no manual server discovery (DNS SRV lookup) needed.
 */
class RadioBrowserApi(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build(),
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
) {
    enum class SearchBy(
        val param: String,
    ) {
        NAME("name"),
        TAG("tag"),
        COUNTRY("country"),
    }

    /** [search]/[searchNearby] results rarely go stale within a session, so repeat lookups (e.g. re-tapping the
     * same country chip, or coming back to Discover) reuse them for [cacheTtlMs] instead of re-hitting the network. */
    private class CacheEntry(
        val stations: List<RadioBrowserStation>,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Throws [IOException] on network failure; callers are expected to catch and surface it.
     * Sorted by `votes` (the directory's community upvote count — the same number shown as the
     * star badge on each result), not `clickcount`, so the visible ranking matches what the badge
     * implies.
     */
    suspend fun search(
        query: String,
        searchBy: SearchBy,
        limit: Int = 30,
    ): List<RadioBrowserStation> =
        fetchStations {
            addQueryParameter(searchBy.param, query)
            addQueryParameter("limit", limit.toString())
            addQueryParameter("hidebroken", "true")
            addQueryParameter("order", "votes")
            addQueryParameter("reverse", "true")
        }

    /**
     * Searches for stations near [latitude]/[longitude] (`geo_lat`/`geo_long`), sorted
     * server-side by proximity (`order=distance`) rather than by client-side computation —
     * matches [search]'s "let the directory do the ranking" approach. [radiusMeters] uses the
     * directory's own `geo_distance` filter param, so stations outside it are excluded before
     * they ever reach this app, not filtered client-side after the fact.
     */
    suspend fun searchNearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        limit: Int = 30,
    ): List<RadioBrowserStation> =
        fetchStations {
            addQueryParameter("geo_lat", latitude.toString())
            addQueryParameter("geo_long", longitude.toString())
            addQueryParameter("geo_distance", radiusMeters.toString())
            addQueryParameter("limit", limit.toString())
            addQueryParameter("hidebroken", "true")
            addQueryParameter("order", "distance")
        }

    /** Shared GET+parse pipeline for the station-search endpoint; [buildQuery] adds this call's own query params. */
    private suspend fun fetchStations(buildQuery: HttpUrl.Builder.() -> Unit): List<RadioBrowserStation> =
        withContext(Dispatchers.IO) {
            val url =
                baseUrl
                    .newBuilder()
                    .addPathSegments("json/stations/search")
                    .apply(buildQuery)
                    .build()
            val cacheKey = url.toString()
            val now = System.currentTimeMillis()
            cache[cacheKey]?.takeIf { it.expiresAtMs > now }?.let { return@withContext it.stations }
            val stations = executeSearchWithRetries(url)
            cache[cacheKey] = CacheEntry(stations, now + cacheTtlMs)
            stations
        }

    /**
     * [baseUrl] round-robins many independent community mirrors, but OkHttp keeps reusing whichever
     * backend connection it first opened — so once that one mirror times out or 503s, every
     * subsequent call on this client keeps hitting it. Evicting the pool between attempts forces
     * each retry to pick a fresh connection (and likely a different mirror) instead of repeating
     * the same failure.
     */
    private fun executeSearchWithRetries(url: HttpUrl): List<RadioBrowserStation> {
        repeat(MAX_RETRIES) {
            try {
                return executeSearch(url)
            } catch (e: IOException) {
                client.connectionPool.evictAll()
            }
        }
        return executeSearch(url)
    }

    private fun executeSearch(url: HttpUrl): List<RadioBrowserStation> {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", APP_USER_AGENT)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return parseStations(response.body?.string().orEmpty())
        }
    }

    /**
     * Registers a play as a "click" with the directory (`GET /json/url/{uuid}`), which is what
     * the directory uses to track its own `clickcount` popularity metric — a separate number from
     * the `votes` [search] results are sorted by, but still worth contributing to since other
     * clients may sort/filter by it. Fire-and-forget: swallows network failures internally since
     * this is best-effort telemetry that must never block or surface as a broken playback attempt.
     */
    suspend fun registerClick(uuid: String) {
        withContext(Dispatchers.IO) {
            try {
                val url =
                    baseUrl
                        .newBuilder()
                        .addPathSegments("json/url")
                        .addPathSegment(uuid)
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", APP_USER_AGENT)
                        .build()
                client.newCall(request).execute().close()
            } catch (e: IOException) {
                // Best-effort telemetry; ignore failures.
            }
        }
    }

    /**
     * Downloads a station's favicon image bytes (feeds the custom-icon pipeline for Discover-added
     * stations, [com.freqcast.util.IconStorage.saveImageBytes]), returning null on any
     * failure — a broken/unreachable/malformed favicon URL should never block adding the station,
     * just leave it on the auto-generated emoji fallback. [url] is an arbitrary external host (the
     * station's own site), not this client's [baseUrl], so a malformed value can throw
     * [IllegalArgumentException] from OkHttp's URL parsing, not just [IOException] — hence the
     * broad catch.
     */
    suspend fun downloadFavicon(url: String): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", APP_USER_AGENT)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "downloadFavicon: HTTP ${response.code} for $url")
                        return@use null
                    }
                    response.body?.bytes()
                }
            } catch (e: Exception) {
                Log.w(TAG, "downloadFavicon: failed for $url", e)
                null
            }
        }

    internal fun parseStations(json: String): List<RadioBrowserStation> {
        val array = JSONArray(json)
        val stations = mutableListOf<RadioBrowserStation>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val url = obj.optString("url_resolved").ifBlank { obj.optString("url") }.trim()
            if (name.isEmpty() || url.isEmpty()) continue
            stations.add(
                RadioBrowserStation(
                    uuid = obj.optString("stationuuid"),
                    name = name,
                    url = url,
                    country = obj.optString("country"),
                    tags = obj.optString("tags"),
                    bitrate = obj.optInt("bitrate", 0),
                    hls = obj.optInt("hls", 0) == 1,
                    countryCode = obj.optString("countrycode"),
                    codec = obj.optString("codec"),
                    votes = obj.optInt("votes", 0),
                    homepage = obj.optString("homepage"),
                    favicon = obj.optString("favicon"),
                    distanceKm = if (obj.has("distance")) obj.optDouble("distance") / 1000.0 else null,
                    sslError = obj.optInt("ssl_error", 0) == 1,
                ),
            )
        }
        return stations
    }

    companion object {
        private const val TAG = "RadioBrowserApi"
        private const val DEFAULT_BASE_URL = "https://all.api.radio-browser.info/"
        private const val DEFAULT_CACHE_TTL_MS = 15 * 60 * 1000L

        /** Total attempts = this + 1 — one initial try, then this many retries against a fresh connection. */
        private const val MAX_RETRIES = 3
    }
}
