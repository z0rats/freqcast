# Testing: CI & screenshot tests

Roborazzi/CI-pipeline specifics — only relevant when touching `.github/workflows/ci.yml` or a Composable covered by a screenshot test. See `AGENTS.md`'s Testing section for the general Kotlin/Robolectric gotchas. Reasons and rejected alternatives live in the cited ADRs (`docs/adr/`).

## Screenshot tests

- `StationItemScreenshotTest`: Roborazzi over Robolectric Native Graphics with the `roborazzi-compose` `captureRoboImage { }` lambda — not `createComposeRule()`, since the manifest has no `ComponentActivity` launcher entry and the lambda self-registers `RoborazziActivity`. References in `app/src/test/screenshots/`.
- `./gradlew recordRoborazziDebug` re-records after an intentional visual change (review the diff); `./gradlew verifyRoborazziDebug` (CI's `test` job) fails on any pixel diff — plain `./gradlew test` doesn't.
- **Baselines are recorded on Ubuntu only**; local macOS verify always fails — ADR-0018.
- **Fix-forward loop** for any change to a Composable covered by a screenshot test (currently `StationItem`): push, let CI's `verifyRoborazziDebug` fail and name the affected tests, re-add the temporary `record-screenshots.yml` workflow (`workflow_dispatch` running `./gradlew recordRoborazziDebug` on `ubuntu-latest`; see `27d145f`, `03cd491`), trigger it, download the `roborazzi-screenshots` artifact, replace the PNGs in `app/src/test/screenshots/`, commit, delete the temporary workflow.

## Compose behaviour tests under Robolectric

Every screen and component has a `createComposeRule()` test (`*ScreenTest`, `ui/components/*Test`) driving it through the UI against a **real ViewModel + in-memory Room** (`testing/inMemoryDatabase()`), `FakePlaybackController` (`emit()` pushes a snapshot), and a `MockWebServer` for directory/stream calls. Shared helpers live in `app/src/test/java/com/freqcast/testing/`. Gotchas:

- `createComposeRule()`'s `ComponentActivity` isn't in the manifest, and `captureRoboImage`'s fix doesn't apply. Use `@get:Rule val ruleChain = composeHostRuleChain(composeTestRule)` (`testing/ComposeHost.kt`), which registers it as an outer `TestRule`. `@Before` is too late (rules run first); `@BeforeClass` is too early (`ApplicationProvider.getApplicationContext()` throws `IllegalStateException`).
- A Composable with an endless loop (`rememberDragDropState`'s per-frame loop in `MainScreen`, ADR-0009; `PlaybackScreen`'s sleep-timer countdown) makes an auto-advancing clock never idle — `waitForIdle()` hangs until `AppNotIdleException` (~5min per test). Set `composeTestRule.mainClock.autoAdvance = false` before `setContent` and step frames yourself (`MainScreenTest.settle()`). With the clock paused, **`performScrollTo()` spins forever** (its scroll animation never gets a frame): click with `performSemanticsAction(SemanticsActions.OnClick)` instead — but that bypasses `enabled = false`, so assert disabled state with `assertIsNotEnabled()`, not by clicking. For a state class alone (`DragDropStateTest`), construct it directly (`internal` constructor) and drive `settle()` via `runOnIdle`.
- Work that hops to a real background thread (OkHttp, `Dispatchers.IO`) and resumes on Main: `composeTestRule.waitUntil` doesn't drain Robolectric's paused main looper, so the continuation never runs. Poll with `composeTestRule.awaitCondition { }` (`testing/AwaitMainLooper.kt`), which `idleFor`s the looper each pass — that also advances its clock, so `delay()`-based debounces elapse.
- `rememberLauncherForActivityResult` (pickers, permission requests, system dialogs): wrap content in `FakeActivityResultRegistry.Provide { }`; it records launches and answers each contract synchronously via `resultFor`. A `StartActivityForResult` result must be a non-null `ActivityResult`.
- State holders on `Animatable` (`SwipeRevealState`, `StationCarouselState`) are tested without a composition: launch on `CoroutineScope(coroutineContext + VirtualFrameClock(this))` inside `runTest` so `animateTo` runs in virtual time.
- Not covered on purpose: any path ending in `FileProvider` (station share, export-with-data, clip export success) — `StationShareTest` must stay the only FileProvider test (AGENTS.md); and `AppFunctionElementNotFoundException` (needs the platform `com.android.extensions.appfunctions` library, absent in Robolectric; SDK 36 doesn't boot under the current Robolectric/JDK).

## CI

- `test` + `verifyRoborazziDebug` run as **one** `./gradlew` invocation — ADR-0019.
- `koverXmlReportDebug` is its own step with `timeout-minutes: 8` + `continue-on-error: true` — ADR-0020 (and its *Revisit* if it hangs again).
- Coverage is informational only — ADR-0016.
