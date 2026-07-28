package com.freqcast.data

import com.freqcast.util.APP_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The latest published GitHub release: its version (without a leading "v") and its page URL. */
data class LatestRelease(
    val version: String,
    val url: String,
)

/**
 * Checks GitHub Releases for the newest published Freqcast build, feeding the Settings screen's
 * "Update available" indicator — this project ships APKs only via GitHub Releases (see README),
 * with no Play Store listing to check instead.
 */
class UpdateChecker(
    private val releaseUrl: String = LATEST_RELEASE_URL,
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build(),
) {
    /** Returns null on any network failure or malformed response — silent, best-effort, never blocks Settings. */
    suspend fun latestRelease(): LatestRelease? =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(releaseUrl)
                        .header("User-Agent", APP_USER_AGENT)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string().orEmpty())
                    val version = json.optString("tag_name").removePrefix("v").removePrefix("V")
                    val url = json.optString("html_url")
                    if (version.isBlank() || url.isBlank()) null else LatestRelease(version, url)
                }
            } catch (e: Exception) {
                null
            }
        }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/z0rats/freqcast/releases/latest"
    }
}

/** True if [latest] (e.g. "3.5.0") is a newer semantic version than [current] (e.g. "3.4.3"). */
fun isNewerVersion(
    current: String,
    latest: String,
): Boolean {
    fun parts(version: String) = version.substringBefore('-').split(".").map { it.toIntOrNull() ?: 0 }
    val currentParts = parts(current)
    val latestParts = parts(latest)
    for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
        val currentPart = currentParts.getOrElse(i) { 0 }
        val latestPart = latestParts.getOrElse(i) { 0 }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}
