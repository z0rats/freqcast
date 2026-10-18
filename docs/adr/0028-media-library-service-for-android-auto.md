# ADR-0028: Android Auto via `MediaLibraryService`; taps go through a reentrant `applyPlayback()`

Status: Accepted · Area: playback / Auto

**Decision.**
- `RadioPlaybackService` extends `MediaLibraryService` (not `MediaSessionService`); manifest action `androidx.media3.session.MediaLibraryService` plus `android.media.browse.MediaBrowserService` for legacy clients; `android:exported="true"`; `res/xml/automotive_app_desc.xml` + `com.google.android.gms.car.application` meta-data.
- Flat browse tree (root → all stations, ADR-0007 order); `mediaId = streamUrl`.
- `onBind()` is `super.onBind(intent) ?: binder`.
- A `MediaLibrarySession` exists from `onCreate()` (`buildMediaSession(streamUrl = null)`).
- `startPlayback()` is split: `buildMediaSession(streamUrl)` runs only from top-level entry points (`onCreate`, `onStartCommand`); `applyPlayback(streamUrl, isRetry)` (player/notification/timeshift/retry) is safe to call reentrantly. An Auto tap reaches `onAddMediaItems`, which resolves `mediaId` against `cachedStations`, calls `applyPlayback()` and returns an empty list.
- `loadBrowsableStations()`/`playFromBrowseTree()` are `internal` for `RadioPlaybackServiceAutoTest`.

**Because.**
- The station list must be browsable from a head unit; Auto binds cross-process.
- `MediaLibraryService.SERVICE_INTERFACE` is a genuinely different string from the old filter, not a supertype it would still match.
- (scar) Unconditionally returning our `LocalBinder` from `onBind` breaks Auto/Assistant.
- `onGetSession()` must be non-null for Auto to browse before anything has played.
- Rebuilding the session whose callback is currently executing isn't supported.
- The `MediaBrowser` Binder round-trip itself needs instrumentation tests, which this project doesn't have.

**Rejected.**
- Routing Auto taps through `startPlayback()` — rebuilds the session mid-callback.
