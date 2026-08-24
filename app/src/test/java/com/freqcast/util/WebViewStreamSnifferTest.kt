package com.freqcast.util

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers everything in [WebViewStreamSniffer] that's pure - [WebViewStreamSniffer.isCandidateStreamUrl],
 * [WebViewStreamSniffer.shouldCaptureRequest], [WebViewStreamSniffer.isTlsHandshakeError], and
 * [WebViewStreamSniffer.finalizeCandidates] - deliberately excluding the actual WebView
 * orchestration (`sniff`/`runSniff`: page load, JS execution, real network capture, the click
 * heuristic's real-world hit rate). Robolectric's `ShadowWebView` is a non-functional stub with no
 * real JS engine - `shouldInterceptRequest`/`onPageFinished` never actually fire under it - and
 * this project has no `androidTest` module. A test that pretended to cover that logic here would
 * be false confidence, worse than no test at all. See AGENTS.md for what to check manually on a
 * real device instead.
 */
class WebViewStreamSnifferTest {
    @Test
    fun `isCandidateStreamUrl rejects common static asset extensions`() {
        assertFalse(WebViewStreamSniffer.isCandidateStreamUrl("https://example.com/app.css"))
        assertFalse(WebViewStreamSniffer.isCandidateStreamUrl("https://example.com/app.js"))
        assertFalse(WebViewStreamSniffer.isCandidateStreamUrl("https://example.com/logo.png"))
        assertFalse(WebViewStreamSniffer.isCandidateStreamUrl("https://example.com/font.woff2"))
        assertFalse(WebViewStreamSniffer.isCandidateStreamUrl("https://example.com/favicon.ico"))
    }

    @Test
    fun `isCandidateStreamUrl accepts an mp3 url`() {
        assertTrue(WebViewStreamSniffer.isCandidateStreamUrl("https://radio.example.com/listen/station/radio.mp3"))
    }

    @Test
    fun `isCandidateStreamUrl accepts an extensionless path`() {
        assertTrue(WebViewStreamSniffer.isCandidateStreamUrl("https://radio.example.com/listen/station"))
    }

    @Test
    fun `isCandidateStreamUrl accepts a query-stringed url`() {
        assertTrue(WebViewStreamSniffer.isCandidateStreamUrl("https://radio.example.com/stream?token=abc123"))
    }

    @Test
    fun `isCandidateStreamUrl rejects non-http schemes`() {
        assertFalse(WebViewStreamSniffer.isCandidateStreamUrl("data:audio/mp3;base64,AAAA"))
        assertFalse(WebViewStreamSniffer.isCandidateStreamUrl("blob:https://example.com/1234"))
    }

    @Test
    fun `shouldCaptureRequest rejects an OPTIONS preflight to an otherwise valid stream url`() {
        assertFalse(WebViewStreamSniffer.shouldCaptureRequest("https://example.com/stream", "OPTIONS"))
        assertTrue(WebViewStreamSniffer.shouldCaptureRequest("https://example.com/stream", "GET"))
    }

    @Test
    fun `shouldCaptureRequest rejects a static asset regardless of method`() {
        assertFalse(WebViewStreamSniffer.shouldCaptureRequest("https://example.com/app.js", "GET"))
    }

    @Test
    fun `isTlsHandshakeError matches only the SSL handshake error code`() {
        assertTrue(WebViewStreamSniffer.isTlsHandshakeError(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE))
        assertFalse(WebViewStreamSniffer.isTlsHandshakeError(WebViewClient.ERROR_TIMEOUT))
        assertFalse(WebViewStreamSniffer.isTlsHandshakeError(WebViewClient.ERROR_HOST_LOOKUP))
    }

    @Test
    fun `finalizeCandidates dedups by url and caps the result`() {
        val request = { url: String -> WebViewStreamSniffer.CapturedRequest(url, emptyMap()) }
        val duplicated = listOf(request("https://a.example/stream"), request("https://a.example/stream"))

        assertEquals(listOf(request("https://a.example/stream")), WebViewStreamSniffer.finalizeCandidates(duplicated))

        val many = (1..20).map { request("https://example.com/stream$it") }
        assertEquals(10, WebViewStreamSniffer.finalizeCandidates(many).size)
    }
}
