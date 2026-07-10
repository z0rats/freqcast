package com.urlradiodroid.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Probes a stream URL before it's saved, so a typo'd or dead URL is caught immediately instead
 * of surfacing as "Connection failed" the first time the user tries to play it.
 *
 * Tries HEAD first (cheap, no body); many Icecast/Shoutcast mounts reject HEAD with 405 or just
 * hang up, so this falls back to GET and closes the response as soon as headers arrive without
 * reading the (effectively infinite) stream body.
 */
class StreamValidator(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build(),
) {
    suspend fun isReachable(url: String): Boolean =
        withContext(Dispatchers.IO) {
            probe(url, "HEAD") || probe(url, "GET")
        }

    private fun probe(
        url: String,
        method: String,
    ): Boolean =
        try {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .method(method, null)
                    .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
}
