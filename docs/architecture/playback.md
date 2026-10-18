# Playback architecture

`RadioPlaybackService`, its narrow `PlaybackController` seam, retry/reconnect, Android Auto, the home screen widget and AppFunctions — only relevant when the change touches playback. Reasons and rejected alternatives live in the cited ADRs (`docs/adr/`).

## Shape

```
callers without a bound service ──PlaybackCommands.start/stop──▶ RadioPlaybackService.onStartCommand
 (Activities pre-bind, widget, alarm,                               │ parse(intent) → Command.Start/Stop
  AppFunctions, ServiceBackedPlaybackController)                    ▼
Compose UI ──PlaybackController (toggle, seek, sleep, clip)──▶  play(request, station, PlayOrigin)
                                                                    │ START_COMMAND / RESUME / IN_SESSION
Android Auto tap ──onAddMediaItems──┐                               ▼
notification skip ──switchToAdjacentStation──┴──────────────▶ applyPlayback()  (reentrant)
                                                                    │
                     PlaybackSession (pure): currentRequest, retry state, error signals
                                                                    │
                     playbackSnapshot: StateFlow ──▶ screens, widget (WidgetStateStore), notification
```

## Service core

- `playbackSnapshot: StateFlow<PlaybackSnapshot>` is the single source of truth for UI — screens, widget and notification collect it instead of polling. The service delegates to `playback/TimeshiftController` (buffer/seek for rewind-live; ADR-0017) and `playback/PlaybackStateStore`.
- Process-death resume from `PlaybackStateStore`, only when the player is `STATE_IDLE` — ADR-0021.
- `PlaybackRequest` (`stationName`, `streamUrl`, `customIcon`, `knownHls`; `PlaybackRequest.of(station)` / `.of(name, url, lookedUpStation)`) is the one value `startPlayback()`/`applyPlayback()` take; all per-stream state is on the pure `playback/PlaybackSession` — ADR-0022.
- Entry points and `PlayOrigin` click counting — ADR-0023. `radioBrowserUuid` and the `isHls` hint are resolved in `onStartCommand` via `repository.getStationByUrl()`, since `PlaybackCommands` only carries name/URL strings; Android Auto passes `station.isHls`/uuid straight from in-memory `cachedStations`.
- Reconnect: capped exponential backoff keyed by attempt id — ADR-0025.
- `startForegroundWithNotification()` returns `Boolean`; `false` = give up — ADR-0026.

## PlaybackController seam

- UI never sees `RadioPlaybackService`; `ServiceBackedPlaybackController` + `FakePlaybackController`; `rememberPlaybackController()`; shared `ConnectionErrorToastEffect` — ADR-0024.
- `toggle()` is one pure `decideToggleAction()` comparing by URL, playing-check before network check — ADR-0024.
- `PlaybackActivity` from the mini player slides up (`slide_up_in.xml` + `stay.xml` via `ActivityOptionsCompat.makeCustomAnimation`); `finish()` is overridden to slide down (`slide_down_out.xml`, classic `overridePendingTransition` — API 34's `overrideActivityTransition` is above minSdk 29), covering every close path. Other entry points skip the slide-up but keep the slide-down.

## Notification

- Skip next/previous walk the station list via `TimeshiftSeekPlayer` → `switchToAdjacentStation`, serialized by `stationSwitchMutex` — ADR-0027.

## Android Auto

- `MediaLibraryService`, manifest/binding/session-in-`onCreate` requirements — ADR-0028. Flat browse tree via `onGetLibraryRoot`/`onGetChildren`/`onGetItem`.
- `buildMediaSession()` vs reentrant `applyPlayback()`; Auto taps never rebuild the session — ADR-0028. When `buildMediaSession(null)` runs (from `onCreate`), the session's `sessionActivity` intent carries no station extras; `PlaybackActivity` reads the live station once bound.

## Home screen widget

- `widget/RadioWidget.kt`: Glance `GlanceAppWidget` (`widget/RadioWidgetReceiver.kt` + `res/xml/radio_widget_info.xml`; `android:initialLayout` is a required-but-unused placeholder). Shows last-played/current station + play-pause + skip, dark palette from `ui/theme/Color.kt`.
- Own state store, written on every `refreshSnapshot()` — ADR-0029.
- Buttons are `ActionCallback`s in `widget/WidgetActions.kt`: `TogglePlaybackAction` stops via `PlaybackCommands.stop` (an `ActionCallback` isn't a bound instance) or resumes; `NextStationAction`/`PreviousStationAction` use pure `util/StationNavigator.kt`, wrapping at both ends. `ActionCallback` needs a no-arg constructor, so each resolves `RadioStationRepository.create(context)` itself (ADR-0001).
- Testing: `RadioWidgetTest` uses `runGlanceAppWidgetUnitTest` against `internal` `RadioWidgetContent` (node-tree assertions). `WidgetActionsTest` calls `onAction()` directly, asserting via `shadowOf(application).nextStartedService`. Both, and `RadioPlaybackServiceAutoTest`'s next/prev test, hit the real `AppDatabase.getDatabase()` singleton — each sticks to a single `@Test` method and clears rows before seeding.

## AppFunctions (Android 16+)

- `appfunctions/RadioAppFunctionService.kt`'s `BaseRadioAppFunctionService` (`@AppFunctionServiceEntryPoint`; KSP generates the concrete service + manifest `<service>`/schema XML) exposes `playStation(stationName)` (case-insensitive name match) / `stopPlayback()` to on-device agents via `PlaybackCommands` — no new playback path. `@RequiresApi(36)`, no back-port. `findStationByName` is `internal` for a plain JUnit test.
- Manual verification (agents never drive a device — see AGENTS.md): `adb shell cmd appfunctions list-app-functions --packageName com.freqcast`, then `adb shell cmd appfunctions execute-app-function` with a JSON payload, or ask an Android 16+ device's Gemini "play <station name> on Freqcast".
