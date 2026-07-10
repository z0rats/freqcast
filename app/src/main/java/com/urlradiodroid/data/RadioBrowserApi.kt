package com.urlradiodroid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A station returned by the [RadioBrowserApi] directory search, not yet saved locally. */
data class RadioBrowserStation(
    val uuid: String,
    val name: String,
    val url: String,
    val country: String,
    val tags: String,
    val bitrate: Int,
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
) {
    enum class SearchBy(
        val param: String,
    ) {
        NAME("name"),
        TAG("tag"),
        COUNTRY("country"),
    }

    /** Throws [IOException] on network failure; callers are expected to catch and surface it. */
    suspend fun search(
        query: String,
        searchBy: SearchBy,
        limit: Int = 30,
    ): List<RadioBrowserStation> =
        withContext(Dispatchers.IO) {
            val url =
                baseUrl
                    .newBuilder()
                    .addPathSegments("json/stations/search")
                    .addQueryParameter(searchBy.param, query)
                    .addQueryParameter("limit", limit.toString())
                    .addQueryParameter("hidebroken", "true")
                    .addQueryParameter("order", "clickcount")
                    .addQueryParameter("reverse", "true")
                    .build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "URLRadioDroid/2.0 (github.com/z0rats/url-radio-droid)")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                parseStations(response.body?.string().orEmpty())
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
                ),
            )
        }
        return stations
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://all.api.radio-browser.info/"
    }
}
