# Station discovery & stream validation

`DiscoverStationsScreen`, resolving a pasted URL into a playable stream, and the Radio Browser directory client — only relevant when the change touches Discover, Add Station, or `StationUrlResolver`/`WebViewStreamSniffer`. **Check here first for any "can't find/add station from URL" report.** Reasons and rejected alternatives live in the cited ADRs (`docs/adr/`).

## Radio Browser client

- `data/RadioBrowserApi.kt`: own `OkHttpClient` + `org.json` (ADR-0001), hits `https://all.api.radio-browser.info/` (round-robins mirrors). `baseUrl`/`client` are constructor params for `MockWebServer` tests.
- `search()` sorts by `order=votes`, matching the star badge (`util/VoteCountFormatter.format()`, shown when `votes > 0`). `registerClick()`: ADR-0023.
- `fetchTags()` **must** pass an explicit `limit` — ADR-0043.

## Main-screen catalog fallback

- Empty local search → inline directory results via `CatalogFallbackSearch` + shared `ui/components/RadioBrowserResultCard.kt` — ADR-0030.
- `util/DebouncedSearch.kt` is the one cancel-latest-wins seam (cancel/debounce/`ensureActive()`): the catalog fallback, `runSearch()` and `searchNearby()` each construct one and only supply `block` and `onResult`.

## Discover screen

- `ui/DiscoverStationsViewModel` + `ui/DiscoverStationsScreen.kt` (`DiscoverStationsActivity`, launched from an `AssistChip` atop `MainScreen`'s list): search by name/genre/country/language/nearby. Doesn't `finish()` after an add (multi-add session); `MainActivity` reloads on return; name collisions auto-rename (ADR-0003). Chip order `NAME, NEARBY, GENRE, COUNTRY, LANGUAGE`; debounce 400ms (`SEARCH_DEBOUNCE_MS`).
- Opens on a default browse list, never a blank prompt — ADR-0031; its region — ADR-0034.
- **GENRE tab**: curated translated chips (`util/GenreCatalog.kt`), one scrolling row in the persistent header, tap → `searchGenre(tag)` without touching the query — ADR-0033; typed-text autocomplete from the cached `radio_tags` catalog (`TagSuggestionsList` in the same slot) — ADR-0043.
- Result rows: `util/CountryFlagEmoji.from(code)` (pure; 2-letter ISO → flag emoji, `null` if malformed) — `stationSubtitle()` shows just the flag; `codec` folds into the bitrate segment (`"128 kbps MP3"`). `homepage` → `OpenInNew` button (`ACTION_VIEW`, catches `ActivityNotFoundException`, not persisted). `sslError` → a `Warning` icon, non-blocking (the directory's HTTPS check failing doesn't mean ExoPlayer will).
- Favicon backfill on add: `RadioBrowserApi.downloadFavicon()` (returns `null` on any failure) + `IconStorage.saveImageBytes()`, fire-and-forget child of the insert's launch — the emoji shows immediately, `customIcon` backfills later or never. Hence `DiscoverStationsViewModel` is the one ViewModel holding a `Context` (`applicationContext`, ADR-0002).

## "Near me"

- `DiscoverSearchMode.NEARBY`: no `TextField`; one-shot permission-then-search flow. `RadioBrowserApi.searchNearby()` uses `geo_lat`/`geo_long`/`geo_distance`/`order=distance`; `distanceKm` (only set for this call) shows as `"📍 2.3 km"`.
- `util/LocationProvider.kt` and the radius/timeout/retry-on-resume behavior — ADR-0042. The ON_RESUME observer reads `uiState` via `rememberUpdatedState` since its `DisposableEffect` is keyed only on `lifecycleOwner`.
- Permission flow lives in `DiscoverStationsScreen` (ViewModel has no permission APIs): `checkSelfPermission` → rationale `AlertDialog` → `RequestPermission()`. Grant → `searchNearby()`; denial → `onLocationPermissionDenied()` sets an inline hint under the chip row.
- **Emulator**: the simulated GPS gives no fix from the in-app location toggle alone — set one via Extended Controls → Location.
- `LocationProviderTest` needs two Robolectric fixes: (1) grant/deny via `Shadows.shadowOf(application).grantPermissions()/denyPermissions()` (`ShadowApplicationPackageManager.grantRuntimePermission()` doesn't affect `ContextCompat.checkSelfPermission()` here); (2) `Shadows.shadowOf(Looper.getMainLooper()).idle()` after `ShadowLocationManager.simulateLocation()` — the main looper is paused by default, so the callback is otherwise silently eaten by the 5s internal timeout.

## Add Station: URL → stream

- `util/StreamValidator.kt` probes before `AddStationViewModel.save()` commits: HEAD, falling back to GET on 405, closing as soon as headers arrive; runs after the cheap name/URL-taken DB checks; failure → `error_stream_unreachable`.
- **`data/StationUrlResolver.kt` is the entry point for anything pasted into `AddStationScreen`'s single URL field** (`stream_url` = "Stream or website URL"): staged pipeline probe → directory → HTML → linked scripts → iframes → WebView — ADR-0036. `internal resolveHomepage()` skips the probe so `StationUrlResolverTest`'s FIFO MockWebServer scripts don't have to account for it.
- Directory match must be unique; ambiguity shows `CandidateStationPickerSheet` — ADR-0037.
- VPN-blocked TLS gets `error_stream_blocked_vpn` from two signals — ADR-0035.

## `util/WebViewStreamSniffer.kt` (stage 5)

- What it is, its hardening and the accepted JS-execution risk — ADR-0038.
- Native `MotionEvent` tap + always a JS click — ADR-0039.
- `OPTIONS` preflights never captured — ADR-0040.
- Captured `CapturedRequest(url, headers)` replayed as a JSON API call, incl. `logo_url` favicon — ADR-0041. `SniffOutcome(candidates, hadTlsFailure)`/`SniffedRequest(url, headers)` live in `StationUrlResolver.kt` so it doesn't import the WebView type; `AddStationViewModel`'s wiring lambda maps `SniffResult` onto them field-for-field.
- Pure, unit-tested pieces (`WebViewStreamSnifferTest`): `isCandidateStreamUrl`, `shouldCaptureRequest(url, method)`, `isTlsHandshakeError(errorCode)`, `finalizeCandidates()` (dedup-by-url + cap). The orchestration around them (page load, JS, touch dispatch) has no automated coverage.
- **Diagnostic logging** (tag `WebViewStreamSniffer`): every intercepted request, `onPageFinished`, every `onConsoleMessage`, `onReceivedSslError`/`onReceivedError`, the play-control match (or none), native `dispatchTouchEvent` consumed/not-consumed, the fallback JS click's return value, the final `SniffResult`. Reproduce with `adb logcat -s WebViewStreamSniffer:D chromium:I`.
- **Manual verification checklist for any `runSniff()` change** — a passing `./gradlew test` proves nothing about it (the four tested functions are one-line filters, not where the bug history lives):
  1. the confirmed Supabase-backed SPA (JS-`fetch()`-only stream URL) still resolves;
  2. an ordinary static-HTML station is unaffected — `fromWebView()` isn't even reached;
  3. no audible sound during resolve (`mediaPlaybackRequiresUserGesture` regression);
  4. the log shows a real `dispatchTouchEvent` consumed result, not just `FALLBACK_CLICK_JS` firing on its own.
