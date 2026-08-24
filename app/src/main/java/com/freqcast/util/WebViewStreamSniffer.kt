package com.freqcast.util

import android.content.Context
import android.net.http.SslError
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.freqcast.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Last-resort fallback for [StationUrlResolver]'s purely-static-text stages: some sites (e.g. a
 * Supabase/custom-API-backed SPA) only ever produce their real stream URL as the result of a
 * runtime JS `fetch()` call - no literal string anywhere in the shipped HTML or JS bundle for a
 * regex to find. This loads the page in a real, throwaway, hardened [WebView], lets its JS run,
 * and passively listens for the live page's own outgoing network requests - plus a best-effort
 * synthetic "click something that looks like a play button" if nothing shows up passively, since
 * many such players only request the actual stream once playback starts.
 *
 * Deliberately Context-holding and *not* referenced by [StationUrlResolver] itself (which stays
 * pure/Context-free and unit-testable) - only [com.freqcast.ui.AddStationViewModel], which already
 * holds an application [Context], wires this in as `StationUrlResolver`'s `webViewSniff` callback.
 *
 * No automated test coverage of the actual WebView orchestration is possible: Robolectric's
 * `ShadowWebView` doesn't execute real JS or fire `shouldInterceptRequest`/`onPageFinished`, and
 * this project has no `androidTest` module - `runSniff`'s page-load/JS/touch-dispatch sequencing
 * needs manual verification on a real device. What *is* pure and unit tested: [isCandidateStreamUrl],
 * [shouldCaptureRequest] (the request-capture filter), [isTlsHandshakeError] (TLS-failure
 * classification, same shape as [com.freqcast.ui.playback.ConnectionRetryPolicy.isRetryableNetworkError]),
 * and [finalizeCandidates] (dedup+cap). That's genuinely everything decidable here without a real
 * WebView - there's no larger hidden ranking/selection algorithm to extract beyond these.
 */
class WebViewStreamSniffer(
    private val context: Context,
) {
    /**
     * A stream-shaped request the sniffer observed, with the headers it was sent with - some sites
     * (e.g. Supabase-backed SPAs) send an `apikey`/`Authorization` header on their data fetch, and a
     * captured URL that itself isn't playable (a JSON API response rather than raw audio) needs
     * those replayed to re-fetch its body outside the WebView; see
     * [com.freqcast.data.StationUrlResolver.SniffedRequest].
     */
    data class CapturedRequest(
        val url: String,
        val headers: Map<String, String>,
    )

    /** What one [sniff] call found - the candidate requests (if any) plus whether it hit a TLS/SSL-shaped failure along the way. */
    data class SniffResult(
        val candidates: List<CapturedRequest>,
        /**
         * True if the page - or any subresource it loaded (e.g. a separate API host the page's JS
         * talks to) - failed with a TLS handshake reset or an untrusted certificate. Observed with
         * at least one RU-hosted station's site resetting the handshake specifically for
         * VPN-routed connections; see [com.freqcast.ui.AddStationViewModel]'s TLS-block handling.
         * This is a separate signal from [candidates] being empty - a page can load perfectly
         * fine over TLS and still just not have a discoverable stream.
         */
        val hadTlsFailure: Boolean,
    )

    /**
     * Loads [url] in a hidden, hardened [WebView] and returns whatever stream-shaped network
     * requests the live page made within [timeoutMs] - never throws, returns an empty result on
     * any failure or timeout, same contract as the other stages' `fetchText()` returning null.
     */
    suspend fun sniff(
        url: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): SniffResult =
        withContext(Dispatchers.Main) {
            val result =
                withTimeoutOrNull(timeoutMs) { runSniff(url) }
                    ?: SniffResult(
                        emptyList(),
                        hadTlsFailure = false,
                    ).also { Log.w(TAG, "sniff timed out after ${timeoutMs}ms") }
            Log.d(TAG, "sniff result: $result")
            result
        }

    private suspend fun runSniff(url: String): SniffResult {
        // shouldInterceptRequest fires on a Chromium IO thread, not this coroutine's thread.
        val captured = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        val tlsFailed = AtomicBoolean(false)
        // Never a bare Application Context directly - known crash risk constructing a WebView
        // that way on some OEM/WebView-provider combinations.
        val webView = WebView(ContextThemeWrapper(context, R.style.Theme_Freqcast))
        return try {
            configure(webView)
            // Never attached to a window (see class doc), so it has no size by default - a 0x0
            // Chromium viewport would make getBoundingClientRect() (used by the touch-dispatch
            // fallback below) meaningless. Sized to a typical phone viewport so a responsive site
            // renders its normal mobile layout, same one a real user would see.
            val density = context.resources.displayMetrics.density
            val widthPx = (VIEWPORT_WIDTH_DP * density).toInt()
            val heightPx = (VIEWPORT_HEIGHT_DP * density).toInt()
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, widthPx, heightPx)
            val pageLoaded = CompletableDeferred<Unit>()
            webView.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val requestUrl = request.url.toString()
                        // A CORS preflight (browser-generated OPTIONS ahead of the real fetch) hits
                        // the exact same URL as the real request that follows it, and always arrives
                        // first - confirmed on a real device (surprise.fm) via its
                        // Access-Control-Request-Headers value merely *listing* "apikey" as a header
                        // name the real request will carry, never an actual apikey value. Left
                        // uncaptured so it can never win runSniff's distinctBy-by-url dedup over the
                        // real, credentialed request for the same URL.
                        val isCandidate = shouldCaptureRequest(requestUrl, request.method)
                        if (isCandidate) captured += CapturedRequest(requestUrl, request.requestHeaders)
                        // Every request, not just candidates - the only visibility we get into
                        // what the page actually did over the network, for manual diagnosis (see
                        // class doc - no automated coverage of this orchestration is possible).
                        Log.d(
                            TAG,
                            "request${if (isCandidate) " [candidate]" else ""} method=${request.method}: $requestUrl",
                        )
                        return null // never actually intercept/proxy - passive observation only
                    }

                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        Log.d(TAG, "onPageFinished: $url (already resolved=${pageLoaded.isCompleted})")
                        // Can fire more than once (iframes, redirects, SPA soft-navigation) -
                        // only the first call should resolve the deferred.
                        if (!pageLoaded.isCompleted) pageLoaded.complete(Unit)
                    }

                    // Certificate-chain validation failure (self-signed/expired/hostname
                    // mismatch/...). Always cancels - same as the default un-overridden behavior -
                    // this override only adds observability, never weakens the check.
                    override fun onReceivedSslError(
                        view: WebView,
                        handler: SslErrorHandler,
                        error: SslError,
                    ) {
                        Log.w(TAG, "onReceivedSslError: primaryError=${error.primaryError} url=${error.url}")
                        tlsFailed.set(true)
                        handler.cancel()
                    }

                    // A handshake that never got far enough to produce a certificate to validate
                    // (e.g. reset by a middlebox) surfaces here instead, as ERROR_FAILED_SSL_HANDSHAKE
                    // - distinct from a plain ERROR_CONNECT/ERROR_TIMEOUT/ERROR_HOST_LOOKUP.
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        Log.w(
                            TAG,
                            "onReceivedError: code=${error.errorCode} desc=${error.description} " +
                                "isMainFrame=${request.isForMainFrame} url=${request.url}",
                        )
                        if (isTlsHandshakeError(error.errorCode)) tlsFailed.set(true)
                    }
                }
            webView.webChromeClient =
                object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?,
                    ): Boolean = false // no popups/new windows

                    // Surfaces the page's own console.log/warn/error - including an uncaught
                    // promise rejection from a gesture-blocked play() call, which is exactly the
                    // signal that would confirm or rule out clickPlayControl's core hypothesis.
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.d(
                            TAG,
                            "console[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                        )
                        return true
                    }
                }

            Log.d(TAG, "loadUrl: $url (viewport ${widthPx}x$heightPx px, density=$density)")
            webView.loadUrl(url)
            withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { pageLoaded.await() }
            delay(PASSIVE_SETTLE_MS)
            Log.d(TAG, "after passive settle: ${captured.size} candidate(s) captured")

            if (captured.isEmpty()) {
                clickPlayControl(webView, density)
                delay(POST_CLICK_SETTLE_MS)
                Log.d(TAG, "after click + post-click settle: ${captured.size} candidate(s) captured")
            }

            SniffResult(finalizeCandidates(captured), tlsFailed.get())
        } finally {
            webView.stopLoading()
            webView.destroy()
            clearAllWebViewData()
        }
    }

    private fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            saveFormData = false
            // Deliberately not NEVER_ALLOW: the goal is to observe http:// stream requests from an
            // https:// page (a common radio-panel pattern) - NEVER_ALLOW would block those
            // subresource requests before shouldInterceptRequest ever sees them.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
            // Correctness, not just hardening: without this an <audio autoplay> element could
            // produce audible sound through the device speakers during a background resolve.
            mediaPlaybackRequiresUserGesture = true
        }
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
    }

    /** Wipes cookies/localStorage/IndexedDB globally so repeated resolves never cross-contaminate. */
    private fun clearAllWebViewData() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * Best-effort: find something that looks like a play control and tap it - primarily with a
     * real, Android-injected touch event, not [WebView.evaluateJavascript]'s `element.click()`,
     * which Chromium's autoplay policy does *not* count as user activation. [configure]
     * deliberately sets `mediaPlaybackRequiresUserGesture = true` (so a background resolve can
     * never produce audible sound on its own), which means a JS-synthesized click alone can't
     * satisfy a native `<audio>`/`<video>` element's `play()` - confirmed against a real SPA
     * (Supabase-backed, `station_settings.stream_url` fetched client-side, wired to a native
     * `<audio>`) where the click heuristic ran but the stream was never actually requested. A
     * [MotionEvent] dispatched through the real Android input path (`View.dispatchTouchEvent`) is
     * what Chromium does treat as a genuine gesture - but since this [WebView] is never attached to
     * a window (see class doc) and it's unverified whether a detached view's dispatched touch
     * actually reaches Chromium's input handling on every OEM/WebView-provider combo, the (weaker,
     * gesture-policy-blocked) JS click still runs afterward unconditionally as a second attempt,
     * not just as an exception fallback.
     */
    private suspend fun clickPlayControl(
        webView: WebView,
        density: Float,
    ) {
        val point = locatePlayControl(webView)
        if (point == null) {
            Log.d(TAG, "clickPlayControl: no play-control element matched")
            return
        }
        // getBoundingClientRect() is in CSS px; View.dispatchTouchEvent() coordinates are in real
        // screen px. Converted via the same `density` the viewport was explicitly measure()/
        // layout()'d with above (not WebView.getScale(), which reflects the last *composited*
        // frame's zoom - unreliable on a view that's never been attached to a window and may never
        // have produced one).
        val x = point.first * density
        val y = point.second * density
        Log.d(TAG, "clickPlayControl: found at CSS(${point.first},${point.second}) -> px($x,$y)")
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 30, MotionEvent.ACTION_UP, x, y, 0)
        try {
            val dispatched =
                runCatching {
                    val downConsumed = webView.dispatchTouchEvent(down)
                    val upConsumed = webView.dispatchTouchEvent(up)
                    downConsumed to upConsumed
                }
            Log.d(TAG, "clickPlayControl: native dispatchTouchEvent result=$dispatched")
        } finally {
            down.recycle()
            up.recycle()
        }
        // Also tried unconditionally, not just as a failure fallback: a detached (never-attached-
        // to-a-window) WebView may silently no-op a dispatched MotionEvent rather than throwing,
        // so "no exception" isn't proof the native tap actually reached Chromium's input handling.
        // Redundant if the native tap did work; a real click() is otherwise the next best signal.
        runCatching {
            webView.evaluateJavascript(
                FALLBACK_CLICK_JS,
            ) { value -> Log.d(TAG, "clickPlayControl: fallback JS click() result=$value") }
        }
    }

    /** Runs [LOCATE_PLAY_CONTROL_JS] and parses its `"x,y"` (CSS px) result, or null if nothing matched. */
    private suspend fun locatePlayControl(webView: WebView): Pair<Float, Float>? {
        val result = CompletableDeferred<String?>()
        runCatching { webView.evaluateJavascript(LOCATE_PLAY_CONTROL_JS) { value -> result.complete(value) } }
            .onFailure { result.complete(null) }
        val raw = withTimeoutOrNull(EVALUATE_JS_TIMEOUT_MS) { result.await() } ?: return null
        val unquoted = raw.removeSurrounding("\"").takeIf { it != "null" } ?: return null
        val parts = unquoted.split(",")
        val x = parts.getOrNull(0)?.toFloatOrNull() ?: return null
        val y = parts.getOrNull(1)?.toFloatOrNull() ?: return null
        return x to y
    }

    companion object {
        private const val TAG = "WebViewStreamSniffer"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val PAGE_LOAD_TIMEOUT_MS = 6_000L

        /**
         * No legitimate site starts streaming audio with zero interaction - [configure]'s
         * `mediaPlaybackRequiresUserGesture = true` rules that out on our end regardless, so this
         * window only ever catches something that was never gesture-gated to begin with (e.g. a
         * "now playing" metadata ping). Short on purpose: it's not waiting for real playback to
         * start, just long enough for an already-in-flight request to land.
         */
        private const val PASSIVE_SETTLE_MS = 1_000L
        private const val POST_CLICK_SETTLE_MS = 3_000L
        private const val EVALUATE_JS_TIMEOUT_MS = 1_500L
        private const val MAX_CANDIDATES = 10

        /** Sized to a typical phone viewport so a responsive site's mobile layout (with a visible play control) renders, not a desktop one. */
        private const val VIEWPORT_WIDTH_DP = 360
        private const val VIEWPORT_HEIGHT_DP = 720

        private val STATIC_ASSET_EXTENSIONS =
            setOf("css", "js", "png", "jpg", "jpeg", "gif", "svg", "webp", "woff", "woff2", "ttf", "ico")

        /**
         * Cheap prefilter, pure and unit-testable without a real WebView: is [url] even worth
         * treating as a stream candidate? Doesn't judge audio-ness beyond ruling out obvious
         * static assets - [StreamValidator.isPlayableStream] downstream is the real judge.
         */
        internal fun isCandidateStreamUrl(url: String): Boolean {
            val httpUrl = url.toHttpUrlOrNull() ?: return false
            val path = httpUrl.encodedPath
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return ext !in STATIC_ASSET_EXTENSIONS
        }

        /**
         * Whether [shouldInterceptRequest] should capture a request for [url]/[method] - combines
         * [isCandidateStreamUrl] with the CORS-preflight exclusion (see the call site's doc: an
         * OPTIONS preflight hits the same URL as the real request that follows it, and must lose
         * [finalizeCandidates]'s by-url dedup to that real, credentialed request).
         */
        internal fun shouldCaptureRequest(
            url: String,
            method: String,
        ): Boolean = isCandidateStreamUrl(url) && !method.equals("OPTIONS", ignoreCase = true)

        /** Whether [errorCode] (from [WebViewClient.onReceivedError]) is TLS-handshake-shaped, as opposed to a plain connect/timeout/DNS failure. */
        internal fun isTlsHandshakeError(errorCode: Int): Boolean =
            errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE

        /** Dedups by URL (keeping the first/real request over a same-URL preflight - see [shouldCaptureRequest]) and caps at [MAX_CANDIDATES]. */
        internal fun finalizeCandidates(captured: List<CapturedRequest>): List<CapturedRequest> =
            captured.distinctBy { it.url }.take(MAX_CANDIDATES)

        /**
         * Finds something that looks like a play control and returns its center point as `"x,y"`
         * (CSS px, viewport-relative) for [clickPlayControl] to tap with a real [MotionEvent] -
         * doesn't click it itself. Matches on visible text/aria-label/class, and only matches a
         * currently-visible element (`offsetParent !== null`). Many SPA radio players only request
         * their real stream once playback actually starts, so passive listening alone misses them.
         */
        private val LOCATE_PLAY_CONTROL_JS =
            """
            (function() {
              var re = /play|▶|слуш|listen|live|эфир|escuchar|在线收听/i;
              var els = document.querySelectorAll(
                'button, a, [role="button"], [class*="play" i], [aria-label*="play" i]'
              );
              for (var i = 0; i < els.length; i++) {
                var e = els[i];
                var label = (e.getAttribute('aria-label')||'') + ' ' + (e.className||'') + ' ' + (e.innerText||'');
                if (re.test(label) && e.offsetParent !== null) {
                  var r = e.getBoundingClientRect();
                  return (r.left + r.width / 2) + ',' + (r.top + r.height / 2);
                }
              }
              return null;
            })();
            """.trimIndent()

        /**
         * Second attempt [clickPlayControl] always runs after the native [MotionEvent] dispatch -
         * the old JS-`element.click()` heuristic. Weaker on its own (Chromium's autoplay policy
         * doesn't count it as user activation), but still worth running in case the native tap
         * silently didn't reach Chromium at all (a detached WebView has no confirmed-reliable
         * `dispatchTouchEvent` behavior - see [clickPlayControl]'s doc).
         */
        private val FALLBACK_CLICK_JS =
            """
            (function() {
              var re = /play|▶|слуш|listen|live|эфир|escuchar|在线收听/i;
              var els = document.querySelectorAll(
                'button, a, [role="button"], [class*="play" i], [aria-label*="play" i]'
              );
              for (var i = 0; i < els.length; i++) {
                var e = els[i];
                var label = (e.getAttribute('aria-label')||'') + ' ' + (e.className||'') + ' ' + (e.innerText||'');
                if (re.test(label) && e.offsetParent !== null) { e.click(); return true; }
              }
              return false;
            })();
            """.trimIndent()
    }
}
