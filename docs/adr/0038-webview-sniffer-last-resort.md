# ADR-0038: A hardened headless WebView sniffer is the last-resort resolver

Status: Accepted · Area: discovery

**Decision.** `util/WebViewStreamSniffer.kt` (wired only by `AddStationViewModel`; `StationUrlResolver` stays Context-free and takes `webViewSniff: (suspend (String) -> SniffOutcome)?`) loads the homepage in a hidden WebView, listens via `shouldInterceptRequest` for stream-shaped requests, then taps a play control (ADR-0039).
- Hardening: `allowFileAccess`/`allowContentAccess`/geolocation off, no popups, `mediaPlaybackRequiresUserGesture = true`, never attached to a `ViewGroup`; SSL errors always `handler.cancel()`.
- Explicit `measure()`/`layout()` to 360×720dp, since a never-attached WebView has no size and `getBoundingClientRect()` would be meaningless.
- `PASSIVE_SETTLE_MS` = 1s.
- Cookies/storage wiped after every call (`CookieManager.removeAllCookies` + `WebStorage.deleteAllData`).
- Verbose diagnostic logging (tag `WebViewStreamSniffer`) — the only way to diagnose a "found nothing" report.

**Because.** Stages 1–4 are regex over static text; a stream URL that only exists as a runtime JS `fetch()` result (confirmed: a Supabase-backed SPA setting `station_settings.stream_url` on a native `<audio>`) is unresolvable by them.
- 1s is enough: `mediaPlaybackRequiresUserGesture` already stops anything gated from starting, so the window only catches what was never gated.
- No audible sound during a background resolve.
- Wiping storage keeps resolves of different stations from cross-contaminating.

**Accepting.** It runs arbitrary unattended third-party JavaScript from whatever URL is pasted, and the synthetic tap may hit the wrong element — a much larger trust surface than plain fetches. Deliberate trade-off, mitigated by the hardening above. Best effort, not guaranteed. Orchestration has no automated coverage (Robolectric's `ShadowWebView` is a stub) — manual checklist in `docs/architecture/discovery.md`.
