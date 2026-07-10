# CLAUDE.md

Android app (Kotlin, Jetpack Compose) for playing internet radio streams (HTTP/HTTPS, incl. HLS `.m3u8`) via ExoPlayer/Media3, with local station storage in Room and background playback.

## Project layout

- Single module: `app/`. No version catalog — dependency versions are hardcoded in `build.gradle` / `app/build.gradle`.
- `app/src/main/java/com/urlradiodroid/`: `data/` (Room + repository), `ui/` (Compose screens + `ui/components`, `ui/playback`, `ui/theme`), `util/`.
- Lint: ktlint (`ktlintCheck` / `ktlintFormat`); `.editorconfig` allows PascalCase-looking names on `@Composable` functions (`ktlint_function_naming_ignore_when_annotated_with = Composable`). CI: `.github/workflows/ci.yml` (test, lint, build), `release.yml` (tag → APK).
- `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` are committed — use `./gradlew` locally to match CI's Gradle version.

## Architecture conventions

- `data/RadioStationRepository` is the only class that talks to `RadioStationDao`/`AppDatabase` directly — `ViewModel`s take a `RadioStationRepository`, never an `AppDatabase`/`Context`. Created via `RadioStationRepository.create(context)`.
- Two `ViewModel` styles are in use, pick based on complexity: `MainViewModel` exposes plain `StateFlow`s and fire-and-forget `fun`s (simple CRUD/search state, no validation). `AddStationViewModel` uses a `UiState` data class + a `Channel`-backed `events: Flow<AddStationEvent>` (`SaveSucceeded`/`SaveFailed`) for one-shot UI effects (validation errors, toasts, navigating back on success) — follow this pattern for any screen with form validation or async operations that need one-shot feedback.
- `RadioPlaybackService` (the `MediaSessionService`) exposes a `playbackSnapshot: StateFlow<PlaybackSnapshot>` (isPlaying, isBuffering, currentMediaId, hasTimeshift, isAtLive, trackTitle, sleepTimerEndAtMs) as the single source of truth for reactive UI state — screens collect this instead of polling the service. It delegates to two extracted collaborators: `playback/TimeshiftController` (buffer file lifecycle + seek math for the rewind/live feature) and `playback/PlaybackStateStore` (SharedPreferences-backed; remembers the last-started station so `onStartCommand` can resume playback if the system kills and restarts the service with a null intent — this is what makes background playback survive process death, not just app backgrounding).
- Any `Activity` that binds `RadioPlaybackService` and passes the instance into a `@Composable` (`MainActivity`, `PlaybackActivity`) must hold it as `mutableStateOf<RadioPlaybackService?>`, not a plain `var`. `ServiceConnection.onServiceConnected` fires asynchronously after the first composition; a plain field mutation there is invisible to Compose, so the composable (and anything derived from it via `playbackSnapshot`, e.g. `isPlaying`/track title/sleep timer) stays stuck at its initial `null` forever.
- Reconnect-on-network-loss uses capped exponential backoff (`RadioPlaybackService.retryDelayMs`: 2s/4s/8s/16s, capped at 30s, `MAX_RETRY_COUNT = 5`) rather than retrying forever — after the limit it surfaces "Connection failed" and stops instead of hammering the stream.
- Station icons (`RadioStation.customIcon`) are currently always auto-generated from the name/URL via `util/EmojiGenerator` (keyword + hash fallback) and shown via `getEmojiBitmap` in the notification. The `station_icon`/`choose_emoji`/`upload_image`/`remove_icon` strings exist but there's no picker UI wired up yet — `customIcon` can only be set today via editing an imported backup JSON directly.

## Database & migrations

- `RadioStation` has unique indices on `name` and `streamUrl` (DB-level, added in schema v3) on top of the pre-existing app-level `isNameTaken`/`isUrlTaken` checks — defense in depth against any insert path that bypasses the UI checks. `AppDatabase` is now on `exportSchema = true` (schema JSONs checked into `app/schemas/`, `room.schemaLocation` set via the `ksp {}` block in `app/build.gradle`) — **schema changes must ship a real `Migration` and a matching schema snapshot, not rely on `fallbackToDestructiveMigrationFrom`** (only allowed for pre-2.0 installs jumping from version 1, since there's no v1 schema snapshot to migrate from safely). `AppDatabase.MIGRATION_2_3` is the template to follow: dedupes any pre-existing `name`/`streamUrl` collisions (`DELETE ... WHERE id NOT IN (SELECT MIN(id) ... GROUP BY ...)`, keeping the oldest row) before creating the unique indices, so upgrading never crashes or silently drops the wrong rows even if duplicates somehow predate the constraint.
- `AppDatabaseMigrationTest` verifies migrations against a hand-built copy of the pre-migration schema (kept in sync with `app/schemas/.../2.json`) rather than `MigrationTestHelper`, since that needs an `androidTest` source set with schema assets + instrumentation, which this project deliberately doesn't have (unit-tests-only, see Testing below) — needs `kspTest 'androidx.room:room-compiler:2.8.4'` so KSP generates Room impl classes for the test-only entities/DB it defines.

## Backup: export / share / import

- Backup JSON shape (`{name, streamUrl, customIcon}[]`) is centralized in `data/StationBackupJson.kt`, used both by `RadioStationRepository.exportStationsToJson()` (all stations, from DB) and by per-station sharing (serializes a single in-memory `RadioStation`, no DB round-trip). `RadioStationRepository.importStationsFromJson()` reads the same shape (JSON via `org.json`) and is wired to MainScreen's overflow menu "Import stations" (SAF `OpenDocument`).
- "Export stations" (overflow menu, all stations) and per-station "Share" (swipe action on `StationItem`, one station) both go through `util/StationShare.kt`, which sends the JSON as a file attachment via `Intent.ACTION_SEND` (so it can go straight to Telegram/WhatsApp/email/etc., not just to a chosen SAF location) — the file name is the station name for per-station shares. Needs a `FileProvider` (declared in `AndroidManifest.xml`, paths in `res/xml/file_paths.xml`, authority hardcoded as `com.urlradiodroid.fileprovider` in `StationShare` — must stay in sync with `applicationId` since there's only one build variant). `testOptions.unitTests.includeAndroidResources = true` in `app/build.gradle` is required for Robolectric to see manifest-declared components (providers, etc.) in tests — without it `FileProvider` can't find its meta-data at all. Note: `FileProvider` statically caches its resolved roots per authority for the process lifetime, so `StationShareTest` deliberately uses a single test method rather than one Activity/cacheDir per `@Test`.
- Currently playing track title (shown in `PlaybackScreen`, sourced from ICY in-band metadata parsed by `StreamRecorder`) has a copy-to-clipboard button next to it, using Compose's `LocalClipboardManager` — no `ClipboardManager` system service needed.

## Testing

- Unit tests only (`app/src/test`, no `androidTest`); CI's `test` job runs `./gradlew test`. Room DB tests and anything needing a `Context` use Robolectric (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [29])`).
- `StreamRecorder`/`TimeshiftController` tests spin up a local `okhttp3.mockwebserver.MockWebServer` instead of mocking OkHttp, since both classes construct their own `OkHttpClient` internally (not injected).
- `RadioPlaybackService`'s pure decision logic (`isHlsUrl`, `retryDelayMs`, `isRetryableNetworkError`) is `internal` (not `private`) specifically so it's callable from tests without exercising the full `MediaSessionService`/ExoPlayer lifecycle.

**Update this file after every significant change to the project** (architecture, module layout, key conventions, build/tooling changes).

**Also keep README.md in sync** — update it whenever deployment/install steps, system requirements, or user-facing features change.
