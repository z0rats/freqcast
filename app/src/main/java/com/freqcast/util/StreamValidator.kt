package com.freqcast.util

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
    /** Whether [url] is reachable at all, regardless of what's actually served there. */
    suspend fun isReachable(url: String): Boolean = probe(url).reachable

    /**
     * Whether [url] is not just reachable but actually looks like a stream, not an ordinary
     * webpage that happened to match one of [com.freqcast.data.StationUrlResolver]'s URL
     * patterns (e.g. a scraped `<iframe>` embed that turned out to be an ad or a video, not a
     * player widget). Guards against exactly that without relying on knowing every such embed's
     * host up front.
     */
    suspend fun isPlayableStream(url: String): Boolean = probe(url).let { it.reachable && it.looksLikeAudio }

    /**
     * Like [isReachable], but also reports the response's `Content-Type` - lets a caller tell a
     * real stream mount (which [com.freqcast.data.StationUrlResolver] should never try to read
     * as HTML) apart from an ordinary reachable webpage.
     */
    suspend fun probe(url: String): Probe =
        withContext(Dispatchers.IO) {
            attempt(url, "HEAD").takeIf { it.reachable } ?: attempt(url, "GET")
        }

    private fun attempt(
        url: String,
        method: String,
    ): Probe =
        try {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .method(method, null)
                    .header("User-Agent", STREAM_USER_AGENT)
                    .build()
            client.newCall(request).execute().use { Probe(it.isSuccessful, it.header("Content-Type")) }
        } catch (e: Exception) {
            Probe(reachable = false, contentType = null)
        }

    data class Probe(
        val reachable: Boolean,
        val contentType: String?,
    ) {
        /** No Content-Type at all is common for bare Icecast/Shoutcast mounts, so it's treated as a stream, not ruled out. */
        val looksLikeAudio: Boolean
            get() {
                val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
                return type.startsWith("audio/") ||
                    type in
                    setOf("application/ogg", "application/vnd.apple.mpegurl", "application/x-mpegurl", "video/mp2t")
            }
    }
}
