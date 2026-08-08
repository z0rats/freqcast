package com.freqcast.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only [WebViewStreamSniffer.isCandidateStreamUrl] is covered here, deliberately - the actual
 * WebView orchestration (`sniff`/`runSniff`: page load, JS execution, real network capture, the
 * click heuristic's real-world hit rate) is intentionally *not* tested. Robolectric's
 * `ShadowWebView` is a non-functional stub with no real JS engine - `shouldInterceptRequest`/
 * `onPageFinished` never actually fire under it - and this project has no `androidTest` module. A
 * test that pretended to cover that logic here would be false confidence, worse than no test at
 * all. See AGENTS.md for what to check manually on a real device instead.
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
}
