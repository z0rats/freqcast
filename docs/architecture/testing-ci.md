# Testing: CI & screenshot tests

Roborazzi/CI-pipeline specifics — only relevant when touching `.github/workflows/ci.yml` or a Composable covered by a screenshot test. See `AGENTS.md`'s Testing section for the general Kotlin/Robolectric gotchas. Reasons and rejected alternatives live in the cited ADRs (`docs/adr/`).

## Screenshot tests

- `StationItemScreenshotTest`: Roborazzi over Robolectric Native Graphics with the `roborazzi-compose` `captureRoboImage { }` lambda — not `createComposeRule()`, since the manifest has no `ComponentActivity` launcher entry and the lambda self-registers `RoborazziActivity`. References in `app/src/test/screenshots/`.
- `./gradlew recordRoborazziDebug` re-records after an intentional visual change (review the diff); `./gradlew verifyRoborazziDebug` (CI's `test` job) fails on any pixel diff — plain `./gradlew test` doesn't.
- **Baselines are recorded on Ubuntu only**; local macOS verify always fails — ADR-0018.
- **Fix-forward loop** for any change to a Composable covered by a screenshot test (currently `StationItem`): push, let CI's `verifyRoborazziDebug` fail and name the affected tests, re-add the temporary `record-screenshots.yml` workflow (`workflow_dispatch` running `./gradlew recordRoborazziDebug` on `ubuntu-latest`; see `27d145f`, `03cd491`), trigger it, download the `roborazzi-screenshots` artifact, replace the PNGs in `app/src/test/screenshots/`, commit, delete the temporary workflow.

## Asserting on Compose *state* under Robolectric

`DragDropStateTest`, `RememberPlaybackControllerTest` use the real `ui-test-junit4`/`ui-test-manifest` `createComposeRule()`. Two gotchas:

- `createComposeRule()`'s `ComponentActivity` isn't in the manifest, and `captureRoboImage`'s fix doesn't apply. Register it as an outer `TestRule` (`shadowOf(appContext.packageManager).addActivityIfNotPresent(ComponentName(appContext, ComponentActivity::class.java))`, via `RuleChain.outerRule(thatRule).around(composeTestRule)`). `@Before` is too late (rules run first); `@BeforeClass` is too early (`ApplicationProvider.getApplicationContext()` throws `IllegalStateException`).
- A Composable with an infinite per-frame loop (`rememberDragDropState`, ADR-0009) makes `waitForIdle()` hang until `AppNotIdleException` (~5min per test). Don't mount the `remember*` wrapper; construct the state class directly (`internal` constructor) against a plain `LazyColumn` and call `composeTestRule.runOnIdle { runBlocking { state.settle() } }` yourself.

## CI

- `test` + `verifyRoborazziDebug` run as **one** `./gradlew` invocation — ADR-0019.
- `koverXmlReportDebug` is its own step with `timeout-minutes: 8` + `continue-on-error: true` — ADR-0020 (and its *Revisit* if it hangs again).
- Coverage is informational only — ADR-0016.
