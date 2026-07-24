package com.freqcast.data

import com.freqcast.util.STREAM_USER_AGENT
import com.freqcast.util.StreamValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What [StationUrlResolver] found for a pasted homepage URL, ready to be saved as a station. */
data class ResolvedStation(
    val streamUrl: String,
    val isHls: Boolean = false,
    val radioBrowserUuid: String? = null,
    /** The directory listing's or homepage's own name for the station, if either was available. */
    val name: String? = null,
    /**
     * A candidate favicon/logo URL for the station, if one could be found - the directory
     * listing's own `favicon` field, or a `<link rel="icon">` on the scraped homepage. Never
     * verified reachable here; the caller downloads it and falls back to the auto-generated emoji
     * icon if that fails.
     */
    val favicon: String? = null,
)

/** Which step of [StationUrlResolver.resolve] is currently running, for a caller to show as progress. */
enum class ResolveStage {
    /** Stage 1: checking whether the station is already cataloged in the Radio Browser directory. */
    SEARCHING_DIRECTORY,

    /** Stage 2 (+3+4): scraping the homepage itself, then its linked scripts or iframes if empty. */
    SCANNING_PAGE,
}

/**
 * Turns a station's homepage URL (all a non-technical user usually has to paste into
 * [com.freqcast.ui.AddStationScreen]) into a playable stream URL, so "add station" doesn't
 * require already knowing the raw Icecast/Shoutcast mount. Tries, in order:
 * 1. [RadioBrowserApi] - the station may already be cataloged there, keyed off its `homepage`
 *    field, with a known-good stream URL (no scraping needed).
 * 2. The homepage's own HTML - `<audio>`/`<source>` tags, linked `.pls`/`.m3u`/`.m3u8` playlists,
 *    inline JSON player configs, or raw Icecast/Shoutcast/AzuraCast URL conventions.
 * 3. Same-origin `<script>` bundles linked from the page, for sites that render the player
 *    client-side (nothing useful in the initial HTML) - re-scanned with the same patterns as
 *    step 2, plus a probe of any same-domain host they reference for the standard AzuraCast
 *    (`/api/nowplaying`) or Icecast (`/status-json.xsl`) status endpoints.
 * 4. Third-party `<iframe>` embeds (e.g. a Zeno.fm/RadioKing/Radio.co player widget), if steps 2
 *    and 3 found nothing - fetched and re-scanned the same way, with common non-player embeds
 *    (social/video/ads/maps/captcha) filtered out, falling back to the same AzuraCast/Icecast
 *    panel probe as step 3 against the iframe's own origin.
 *
 * Every URL this produces is still checked with [StreamValidator.isPlayableStream] before being
 * returned, same as a manually typed stream URL - a resolved candidate that turns out unreachable
 * *or* that turns out to just be another webpage (e.g. an ad/video iframe whose body coincidentally
 * matched one of the patterns above) is discarded (or, where more than one candidate exists,
 * skipped in favor of the next). The whole resolve is capped at [RESOLVE_TIMEOUT_MS] so a slow or
 * unresponsive site can't hang the caller indefinitely.
 */
class StationUrlResolver(
    private val radioBrowserApi: RadioBrowserApi = RadioBrowserApi(),
    private val streamValidator: StreamValidator = StreamValidator(),
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build(),
) {
    suspend fun resolve(
        homepageUrl: String,
        onStage: (ResolveStage) -> Unit = {},
    ): ResolvedStation? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                val normalizedUrl = withScheme(homepageUrl)
                val host = hostOf(normalizedUrl) ?: return@withTimeoutOrNull null
                onStage(ResolveStage.SEARCHING_DIRECTORY)
                fromDirectory(host) ?: run {
                    onStage(ResolveStage.SCANNING_PAGE)
                    fromHtml(normalizedUrl, host)
                }
            }
        }

    /** Stage 1: the directory may already carry this station, discoverable via its homepage field. */
    private suspend fun fromDirectory(host: String): ResolvedStation? {
        val keyword = searchKeyword(host) ?: return null
        val candidates =
            try {
                radioBrowserApi.search(keyword, RadioBrowserApi.SearchBy.NAME, limit = 50)
            } catch (e: Exception) {
                return null
            }
        val match = candidates.firstOrNull { hostOf(it.homepage) == host } ?: return null
        return coroutineScope {
            // The stream-playability probe and the (possible) homepage favicon fetch are
            // independent round-trips once match is known - run them concurrently instead of one
            // after the other, same rationale as fromHtml's parallel candidate validation below.
            // Cancelled rather than awaited if the stream turns out unplayable, so an unplayable
            // match doesn't pay for a favicon fetch whose result would just be discarded anyway.
            val faviconDeferred =
                async(Dispatchers.IO) {
                    match.favicon.takeIf(String::isNotBlank) ?: faviconFromHomepage(match.homepage)
                }
            val playable = withContext(Dispatchers.IO) { streamValidator.isPlayableStream(match.url) }
            if (!playable) {
                faviconDeferred.cancel()
                return@coroutineScope null
            }
            ResolvedStation(
                streamUrl = match.url,
                isHls = match.hls,
                radioBrowserUuid = match.uuid.takeIf(String::isNotBlank),
                name = match.name.takeIf(String::isNotBlank),
                favicon = faviconDeferred.await(),
            )
        }
    }

    /**
     * A supplementary favicon lookup for a directory match whose own listing doesn't carry one -
     * common even for well-cataloged stations (Radio Browser's `favicon` field is community-filled
     * and often just left blank). [fromDirectory] otherwise never fetches the homepage at all (that's
     * the point of a directory match - no scrape needed for the stream itself), so without this a
     * blank directory favicon means no favicon candidate ever surfaces for this class of station,
     * even when the homepage itself declares a perfectly good one. Fetches [homepageUrl] (already
     * known-good, since [fromDirectory] just matched it) far enough to read its `<link rel="icon">`
     * tags, the same way [fromHtml] would for a homepage paste that wasn't already cataloged.
     */
    private fun faviconFromHomepage(homepageUrl: String): String? {
        if (homepageUrl.isBlank()) return null
        val normalized = withScheme(homepageUrl)
        val html = fetchText(normalized) ?: return null
        return extractFavicon(html, normalized)
    }

    /** Stage 2 (+3+4): scrape the homepage itself, then its linked scripts or iframes if that comes up empty. */
    private suspend fun fromHtml(
        homepageUrl: String,
        host: String,
    ): ResolvedStation? {
        val html = fetchText(homepageUrl) ?: return null
        val pageName = extractTitle(html)
        val pageFavicon = extractFavicon(html, homepageUrl)
        val direct = extractCandidates(html, homepageUrl)
        val candidates =
            direct
                .ifEmpty { fromLinkedScripts(html, homepageUrl, host) }
                .ifEmpty { fromIframes(html, homepageUrl) }
        val ranked = rank(candidates, host)
        // Tried in rank order, stopping at the first that validates - not all at once. rank()
        // exists precisely to put the most-likely-correct candidate first, and a page's script
        // bundle can easily contain other URL-shaped strings that are nothing to do with this
        // station (a third-party fallback/jingle host, an SDK's own telemetry endpoint, ...): those
        // are usually slow to fail (a dead/wrong host can take the full HEAD+GET timeout budget to
        // come back 404), so validating every candidate concurrently means paying that cost even
        // when the real, correctly-ranked-first candidate already answered almost immediately.
        val streamUrl =
            ranked.firstNotNullOfOrNull { url ->
                materialize(url)?.takeIf { streamValidator.isPlayableStream(it) }
            }
        return streamUrl?.let {
            ResolvedStation(
                it,
                isHls = it.contains(".m3u8", ignoreCase = true),
                name = pageName,
                favicon = pageFavicon,
            )
        }
    }

    /**
     * A candidate favicon for [baseUrl]'s page: one of its `<link rel="icon">`/`<link
     * rel="shortcut icon">`/`<link rel="apple-touch-icon">` tags, resolved to an absolute URL. Not
     * verified reachable - the caller (favicon download) treats a 404 the same as never having
     * found a candidate at all. Unlike a stream URL, a favicon is never guessed at a standard path
     * (e.g. `/favicon.ico`) without the page actually declaring one - most of this class's other
     * candidate sources rely on convention because there's no alternative, but here the page
     * itself is already in hand, so acting only on what it actually says avoids firing requests at
     * hosts that were never confirmed to serve anything at that path.
     *
     * A page listing several of these (common: a plain `.ico` for old browsers plus a PNG
     * `apple-touch-icon` for everything else) prefers the first non-`.ico` one - [IconStorage]
     * can decode a `.ico` favicon itself now, but a real PNG the page already offers is one less
     * thing that can go wrong, and still the only option for the handful of `.ico` variants
     * (indexed palettes, compressed DIBs) [IcoDecoder] doesn't cover.
     */
    internal fun extractFavicon(
        html: String,
        baseUrl: String,
    ): String? {
        val candidates =
            FAVICON_LINK_REGEX
                .findAll(html)
                .mapNotNull { HREF_REGEX.find(it.value)?.groupValues?.get(1) }
                .mapNotNull { resolveAgainst(it, baseUrl) }
                .toList()
        return candidates.firstOrNull { !it.substringBefore('?').endsWith(".ico", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    /** The homepage's own `<title>`, a decent default station name for a page with no better one. */
    internal fun extractTitle(html: String): String? =
        TITLE_REGEX
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::unescapeHtmlEntities)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    /** Follows same-origin `<script src>` bundles - for sites whose player is rendered client-side. */
    private suspend fun fromLinkedScripts(
        html: String,
        baseUrl: String,
        host: String,
    ): List<Candidate> {
        val scriptUrls =
            linkedUrls(html, baseUrl, SCRIPT_SRC_REGEX, MAX_LINKED_SCRIPTS) { it == host || it.endsWith(".$host") }

        val candidates = mutableListOf<Candidate>()
        val discoveredOrigins = linkedSetOf<String>()
        val subdomainRegex = Regex("""(https?://[\w-]+\.${Regex.escape(host)})""", RegexOption.IGNORE_CASE)

        val bodies =
            coroutineScope {
                scriptUrls.map { scriptUrl -> async(Dispatchers.IO) { scriptUrl to fetchText(scriptUrl) } }.awaitAll()
            }
        for ((scriptUrl, body) in bodies) {
            if (body == null) continue
            candidates += extractCandidates(body, scriptUrl)
            subdomainRegex.findAll(body).forEach { discoveredOrigins += it.groupValues[1].lowercase() }
        }

        // The AzuraCast/Icecast panel probe below only matters for a script that references a
        // self-hosted panel origin *without* the stream URL itself appearing anywhere in the
        // script text - if extractCandidates already found something above, every discovered
        // origin's probe would just be 1-2 more round-trips (each almost always a plain 404, since
        // most subdomains a minified bundle happens to mention - analytics, a CMS, unrelated
        // mirrors - were never an AzuraCast/Icecast panel to begin with) spent confirming what's
        // already known. A large SPA bundle can easily reference several such subdomains, so
        // skipping this when unnecessary is a real, not theoretical, latency save.
        if (candidates.isEmpty()) {
            val panelResults =
                coroutineScope {
                    discoveredOrigins
                        .map { origin ->
                            async(Dispatchers.IO) { origin to panelStreamUrl(origin) }
                        }.awaitAll()
                }
            panelResults.forEach { (origin, streamUrl) ->
                if (streamUrl != null) candidates += Candidate(streamUrl, origin)
            }
        }

        return candidates
    }

    /**
     * Follows third-party `<iframe>` embeds - the common way a small station drops in a
     * ready-made player widget (Zeno.fm, RadioKing, Radio.co, ...) instead of writing its own.
     * Unlike [fromLinkedScripts] these are expected to be cross-origin, so obvious non-player
     * embeds (social/video/ads/maps/captcha widgets) are filtered out by host instead.
     */
    private suspend fun fromIframes(
        html: String,
        baseUrl: String,
    ): List<Candidate> {
        val iframeUrls =
            linkedUrls(html, baseUrl, IFRAME_SRC_REGEX, MAX_IFRAMES) { urlHost ->
                IFRAME_HOST_BLOCKLIST.none { blocked -> urlHost == blocked || urlHost.endsWith(".$blocked") }
            }

        return coroutineScope {
            iframeUrls
                .map { iframeUrl ->
                    async(Dispatchers.IO) {
                        val fromBody = fetchText(iframeUrl)?.let { extractCandidates(it, iframeUrl) }.orEmpty()
                        if (fromBody.isNotEmpty()) return@async fromBody
                        val origin =
                            iframeUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return@async emptyList()
                        panelStreamUrl(origin)?.let { listOf(Candidate(it, iframeUrl)) }.orEmpty()
                    }
                }.awaitAll()
                .flatten()
        }
    }

    /** URLs an `<... src="...">` attribute matched by [regex] resolves to, deduped, host-filtered by [hostAllowed] and capped at [max]. */
    private fun linkedUrls(
        html: String,
        baseUrl: String,
        regex: Regex,
        max: Int,
        hostAllowed: (String) -> Boolean,
    ): List<String> =
        regex
            .findAll(html)
            .mapNotNull { resolveAgainst(it.groupValues[1], baseUrl) }
            .filter { url ->
                url
                    .toHttpUrlOrNull()
                    ?.host
                    ?.lowercase()
                    ?.let(hostAllowed) == true
            }.distinct()
            .take(max)
            .toList()

    /** Well-known status endpoints for the two most common self-hosted radio panels, at [origin] (`scheme://host`). */
    internal fun panelStreamUrl(origin: String): String? = fromAzuraCast(origin) ?: fromIcecast(origin)

    private fun fromAzuraCast(origin: String): String? {
        val body = fetchText("$origin/api/nowplaying") ?: return null
        return try {
            val stations = JSONArray(body)
            if (stations.length() == 0) return null
            stations
                .getJSONObject(0)
                .getJSONObject("station")
                .optString("listen_url")
                .takeIf(String::isNotBlank)
        } catch (e: Exception) {
            null
        }
    }

    private fun fromIcecast(origin: String): String? {
        val body = fetchText("$origin/status-json.xsl") ?: return null
        return try {
            val source = JSONObject(body).getJSONObject("icestats").opt("source")
            val obj =
                when (source) {
                    is JSONArray -> if (source.length() > 0) source.getJSONObject(0) else return null
                    is JSONObject -> source
                    null -> return null
                    else -> return null
                }
            obj.optString("listenurl").takeIf(String::isNotBlank)
        } catch (e: Exception) {
            null
        }
    }

    /** Resolves a `.pls`/`.m3u` playlist link to the stream URL inside it; passes anything else through. */
    private fun materialize(url: String): String? =
        when {
            url.endsWith(".pls", ignoreCase = true) -> fetchText(url)?.let(::parsePls)
            url.endsWith(".m3u", ignoreCase = true) -> fetchText(url)?.let(::parseM3u)
            else -> url
        }

    private fun parsePls(text: String): String? =
        text
            .lineSequence()
            .firstOrNull { it.trim().startsWith("File1=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.startsWith("http", ignoreCase = true) }

    private fun parseM3u(text: String): String? =
        text
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("http", ignoreCase = true) }

    /** Scans [text] (HTML or JS) for anything that looks like a stream URL, keeping surrounding context for [rank]. */
    internal fun extractCandidates(
        text: String,
        baseUrl: String,
    ): List<Candidate> {
        val unescaped = text.replace("\\/", "/")
        val results = mutableListOf<Candidate>()

        fun collect(
            regex: Regex,
            resolveRelative: Boolean,
        ) {
            regex.findAll(unescaped).forEach { m ->
                val raw = if (m.groupValues.size > 1) m.groupValues[1] else m.value
                val url = if (resolveRelative) resolveAgainst(raw, baseUrl) else raw.takeIf { it.startsWith("http") }
                if (url != null) {
                    val contextStart = maxOf(0, m.range.first - 200)
                    results += Candidate(url, unescaped.substring(contextStart, m.range.first))
                }
            }
        }

        collect(AUDIO_SRC_REGEX, resolveRelative = true)
        collect(STREAM_KEY_REGEX, resolveRelative = false)
        collect(PLAYLIST_REF_REGEX, resolveRelative = true)
        collect(EXTENSION_URL_REGEX, resolveRelative = false)
        collect(PORT_STREAM_REGEX, resolveRelative = false)
        collect(PATH_STREAM_REGEX, resolveRelative = false)

        return results
    }

    /**
     * Orders candidates by how likely they are to be *this* station's stream, for pages whose
     * player config lists a whole network of sibling stations (see [Candidate]'s context window):
     * a candidate hosted on a subdomain of the target site outranks a third-party host, and beyond
     * that a candidate whose nearby text shares a word with the site's own domain label outranks
     * one that doesn't (e.g. "jazz" in both "radiojazzfm.ru" and a nearby `"title":"Jazz FM"`).
     */
    internal fun rank(
        candidates: List<Candidate>,
        host: String,
    ): List<String> {
        val label = searchKeyword(host) ?: return candidates.map { it.url }.distinct()
        return candidates
            .distinctBy { it.url }
            .sortedByDescending { candidate ->
                val urlHost =
                    candidate.url
                        .toHttpUrlOrNull()
                        ?.host
                        ?.lowercase()
                        .orEmpty()
                val hostScore = if (urlHost.contains(label)) 2 else 0
                val contextScore =
                    WORD_REGEX
                        .findAll(candidate.context)
                        .map { it.value.lowercase() }
                        .count { it.length >= 3 && it !in GENERIC_WORDS && label.contains(it) }
                hostScore + contextScore
            }.map { it.url }
    }

    private fun resolveAgainst(
        ref: String,
        baseUrl: String,
    ): String? {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        return base.resolve(ref)?.toString()
    }

    /** The registrable-ish label RadioBrowser station names tend to contain, e.g. "silver" from "silver.ru". */
    internal fun searchKeyword(host: String): String? {
        val labels = host.split(".")
        if (labels.size < 2) return null
        return labels[labels.size - 2].takeIf { it.length >= 3 }
    }

    internal fun hostOf(url: String): String? =
        try {
            withScheme(url)
                .toHttpUrlOrNull()
                ?.host
                ?.removePrefix("www.")
                ?.lowercase()
        } catch (e: Exception) {
            null
        }

    private fun withScheme(url: String): String = if (!url.contains("://")) "http://$url" else url

    /**
     * GETs [url] as text, capped at [MAX_FETCH_BYTES] via [okhttp3.Response.peekBody] - a page is
     * never anywhere near that large, but an audio stream mistaken for a homepage (e.g. a
     * directory-search false positive) is unbounded, and peeking rather than reading the full
     * body means this returns instead of hanging on one.
     */
    private fun fetchText(url: String): String? =
        try {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", STREAM_USER_AGENT)
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.peekBody(MAX_FETCH_BYTES).string()
            }
        } catch (e: Exception) {
            null
        }

    /** A stream-URL-shaped match plus the text just before it, used only to disambiguate in [rank]. */
    internal data class Candidate(
        val url: String,
        val context: String,
    )

    companion object {
        private const val MAX_LINKED_SCRIPTS = 8
        private const val MAX_IFRAMES = 4
        private const val MAX_FETCH_BYTES = 2_000_000L

        /** Overall budget for one [resolve] call, however many network round-trips it takes. */
        private const val RESOLVE_TIMEOUT_MS = 20_000L

        private val TITLE_REGEX = Regex("""<title\b[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
        private val HTML_ENTITIES =
            mapOf(
                "&amp;" to "&",
                "&lt;" to "<",
                "&gt;" to ">",
                "&quot;" to "\"",
                "&#39;" to "'",
                "&apos;" to "'",
                "&nbsp;" to " ",
            )

        private fun unescapeHtmlEntities(text: String): String =
            HTML_ENTITIES.entries.fold(text) { acc, (entity, char) -> acc.replace(entity, char) }

        /** Excluded from [rank]'s context-overlap scoring: generic enough to match almost any radio site's domain. */
        private val GENERIC_WORDS =
            setOf("radio", "live", "stream", "station", "online", "music", "player", "net", "www", "http", "https")
        private val WORD_REGEX = Regex("[A-Za-z]+")
        private val AUDIO_SRC_REGEX =
            Regex("""<(?:audio|source)\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val SCRIPT_SRC_REGEX = Regex("""<script\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val IFRAME_SRC_REGEX = Regex("""<iframe\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val FAVICON_LINK_REGEX =
            Regex("""<link\b[^>]*\brel=["'][^"']*icon[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val HREF_REGEX = Regex("""\bhref=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        /**
         * Hosts an `<iframe>` on a station's homepage is essentially never a player widget for -
         * video/social/ad/analytics embeds. Not meant to be exhaustive (new ones show up
         * constantly): it only exists to skip requests that would waste the resolve budget on
         * something obviously irrelevant. [StreamValidator.isPlayableStream]'s Content-Type
         * check, not this list, is what actually guards against a false-positive candidate slipping
         * through from a host this list doesn't happen to cover.
         */
        private val IFRAME_HOST_BLOCKLIST =
            setOf(
                "youtube.com",
                "youtube-nocookie.com",
                "facebook.com",
                "twitter.com",
                "x.com",
                "instagram.com",
                "tiktok.com",
                "vimeo.com",
                "spotify.com",
                "soundcloud.com",
                "google.com",
                "recaptcha.net",
                "doubleclick.net",
                "googletagmanager.com",
                "googlesyndication.com",
                "disqus.com",
                "vk.com",
                "ok.ru",
                "rutube.ru",
                "mail.ru",
                "yandex.ru",
                "dzen.ru",
            )
        private val PLAYLIST_REF_REGEX =
            Regex("""["']([^"'\s]+\.(?:pls|m3u8?))(?:["'?]|\s)""", RegexOption.IGNORE_CASE)
        private val STREAM_KEY_REGEX =
            Regex(
                """"(?:stream_url|streamUrl|stream|audio_url|audioUrl|mp3Url|radioUrl)"\s*:\s*"(https?:[^"]+)"""",
                RegexOption.IGNORE_CASE,
            )
        private val EXTENSION_URL_REGEX =
            Regex(
                """https?://[\w.-]+(?::\d{2,5})?(?:/[\w\-./%]*)?\.(?:mp3|aac|ogg|opus|m3u8)(?:\?[\w=&%.\-]*)?""",
                RegexOption.IGNORE_CASE,
            )
        private val PORT_STREAM_REGEX =
            Regex("""https?://[\w.-]+:(?:8000|8080|8888|9000)(?:/[\w\-./%;]*)?""", RegexOption.IGNORE_CASE)
        private val PATH_STREAM_REGEX =
            Regex(
                """https?://[\w.-]+(?::\d{2,5})?/(?:stream|listen|live|;stream)(?:\.mp3)?[\w\-./%;]*""",
                RegexOption.IGNORE_CASE,
            )
    }
}
