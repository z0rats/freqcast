# Architecture Decision Records

One file per decision: `NNNN-slug.md`. Area pages in `docs/architecture/` describe *what is*; an ADR records *why it is that way and what was rejected*. Area pages cite ADRs by number (`ADR-0033`) instead of re-arguing them.

## Format

```
# ADR-NNNN: <the decision, as a statement>

Status: Accepted · Area: <area>

**Decision.**   what is done — concrete names, values, call sites
**Because.**    why; mark a reason learned from a real failure as "(scar)"
**Accepting.**  the cost knowingly taken on (optional)
**Rejected.**   - alternative — reason. One line each; never drop the reason.
**Revisit.**    the condition that would reopen it (optional, for provisional decisions)
```

Where a reason was never written down, say `*reason not recorded*` instead of inventing one.

## Rules

- **When to write one.** A competent person could reasonably have chosen otherwise, *and* the choice changes the code. Write it in the same commit as the change.
- **Numbers are frozen.** Never renumber or reuse a number. Next number = highest + 1.
- **Never delete.** A replaced decision gets `Status: Superseded by ADR-NNNN` and stays; the new ADR says what it supersedes.
- **Flipping a decision has a duty:** check every ADR that cites it and reopen the ones that no longer hold.
- **An invariant must be a scar, not a prediction.** A "never do X" rule earns an ADR only after X actually broke something; record what broke.
- **Prose may not contradict an ADR.** If an area page and an ADR disagree, one of them is a bug — fix it, don't leave both.
- **Not here:** procedures (release steps, manual check-lists), test-harness gotchas with no alternative to choose between, and history narrative — those stay in `docs/architecture/`, `AGENTS.md` or `git log`.

## Index

| ADR | Decision | Area |
|---|---|---|
| [0001](0001-minimize-dependencies.md) | Hand-roll small pieces instead of adding a dependency | cross-cutting |
| [0002](0002-repository-only-db-gateway.md) | `RadioStationRepository` is the only gateway to the station table; ViewModels hold no `Context` | cross-cutting |
| [0003](0003-single-seam-for-non-interactive-adds.md) | One seam for adding a station without per-field validation | data |
| [0004](0004-edit-copies-onto-loaded-station.md) | The edit path copies form fields onto the loaded station | data |
| [0005](0005-delete-immediate-with-undo.md) | Deleting a station is immediate and undoable, not confirmation-gated | data / main screen |
| [0006](0006-real-migrations-only.md) | Every schema change ships a real `Migration` and a schema snapshot | data |
| [0007](0007-manual-sort-order.md) | A free manual `sortOrder` replaces favorites/pinning | data |
| [0008](0008-hand-rolled-drag-reorder.md) | Hand-rolled long-press drag on the whole `LazyColumn` | ui / main screen |
| [0009](0009-drag-settle-once-per-frame.md) | Drag swap-search and auto-scroll run at most once per frame | ui / main screen |
| [0010](0010-drag-lookup-by-key.md) | The dragged item is identified by key, never by index | ui / main screen |
| [0011](0011-drag-edge-crossing-swap.md) | Swap when the dragged card's leading edge crosses the neighbor's edge | ui / main screen |
| [0012](0012-curated-pack-seeded-once.md) | The curated pack is seeded once into a fresh install, then is ordinary data | data |
| [0013](0013-swipe-hint-on-first-curated-station.md) | One-time swipe hint on the first curated station, targeting Delete | ui / main screen |
| [0014](0014-update-check-runtime-installer-detection.md) | The GitHub update check is skipped by a runtime installer check, not a build flavor | release |
| [0015](0015-fdroid-reproducible-builds.md) | F-Droid ships our own signed APK via reproducible builds | release |
| [0016](0016-coverage-is-informational.md) | Coverage is informational, never a merge gate | testing / CI |
| [0017](0017-timeshift-datasource-factory-seam.md) | `TimeshiftController` returns a `DataSource.Factory`, not a `MediaItem`/`MediaSource` | playback / testing |
| [0018](0018-screenshot-baselines-ubuntu-only.md) | Screenshot baselines are recorded on Ubuntu only | testing / CI |
| [0019](0019-ci-single-gradle-invocation.md) | CI runs `test` and `verifyRoborazziDebug` in one `./gradlew` invocation | CI |
| [0020](0020-kover-separate-ci-step.md) | `koverXmlReportDebug` is its own CI step with a timeout and `continue-on-error` | CI |
| [0021](0021-process-death-resume.md) | Resume after process death from `PlaybackStateStore`, only when the player is idle | playback |
| [0022](0022-playback-session-pure-state-machine.md) | Per-stream state lives in a pure `PlaybackSession`, not in the service | playback |
| [0023](0023-playback-commands-single-entry.md) | Every unbound caller starts/stops playback through `PlaybackCommands` | playback |
| [0024](0024-playback-controller-seam.md) | Playback UI binds through a narrow `PlaybackController` with one pure toggle decision | playback / ui |
| [0025](0025-capped-retry-backoff.md) | Reconnect with capped exponential backoff, keyed by attempt id | playback |
| [0026](0026-start-foreground-may-fail.md) | `startForegroundWithNotification()` can fail and returns `Boolean` | playback |
| [0027](0027-notification-skip-navigates-stations.md) | Notification skip buttons navigate the station list | playback |
| [0028](0028-media-library-service-for-android-auto.md) | Android Auto via `MediaLibraryService`; taps go through a reentrant `applyPlayback()` | playback / Auto |
| [0029](0029-widget-own-state-store.md) | The widget has its own `WidgetStateStore` | widget |
| [0030](0030-catalog-fallback-on-empty-local-search.md) | An empty main-screen search falls back to the directory, inline | discovery / main screen |
| [0031](0031-discover-default-browse.md) | Discover never opens on a blank prompt | discovery |
| [0033](0033-genre-chips-curated-catalog.md) | GENRE quick-select chips come from a curated, translated catalog | discovery |
| [0033](0033-genre-chips.md) | GENRE chips: a curated translated catalog, one scrolling row in the header, searching without touching the query | discovery |
| [0034](0034-default-browse-region-resolution.md) | Default-browse region follows the in-app language first, then the raw system region | discovery |
| [0035](0035-vpn-tls-block-distinct-error.md) | A TLS reset under VPN gets its own error, from two signals | discovery |
| [0036](0036-url-resolver-staged-pipeline.md) | Everything pasted into Add Station goes through one staged `StationUrlResolver` | discovery |
| [0037](0037-directory-match-must-be-unique.md) | A directory homepage match must be unique; ambiguity asks the user | discovery |
| [0038](0038-webview-sniffer-last-resort.md) | A hardened headless WebView sniffer is the last-resort resolver | discovery |
| [0039](0039-native-tap-plus-js-click.md) | The sniffer taps play with a real `MotionEvent`, then always also a JS click | discovery |
| [0040](0040-sniffer-ignores-cors-preflight.md) | The sniffer never captures `OPTIONS` requests | discovery |
| [0041](0041-replay-json-api-with-captured-headers.md) | A captured JSON API request is replayed with its captured headers | discovery |
| [0042](0042-location-manager-coarse-only.md) | "Near me" uses plain `LocationManager`, coarse location, one fix | discovery |
| [0043](0043-tag-autocomplete-cached-catalog.md) | GENRE autocomplete uses a local cache of the full `/json/tags` catalog | discovery |
| [0044](0044-app-shortcuts.md) | Two dynamic App Shortcuts into an exported `PlaybackActivity` | shortcuts |
| [0045](0045-alarms-use-set-alarm-clock.md) | Wake-up alarms use `setAlarmClock`, one `PendingIntent` per Room row | alarms |
| [0046](0046-alarm-receivers-run-blocking.md) | Alarm receivers do their Room work with `runBlocking` | alarms |
| [0047](0047-legacy-alarm-migration.md) | The single-alarm SharedPreferences format migrates lazily from three places | alarms |
| [0048](0048-backup-json-excludes-device-local-fields.md) | Backup JSON excludes `sortOrder` and `isFavorite` | backup |
| [0049](0049-import-sniffs-content.md) | Import detects the format from file content, not extension or MIME | backup |
| [0050](0050-orientation-seam.md) | Orientation is a plain `LocalConfiguration` check; only `PlaybackScreen` branches on it | ui |
| [0051](0051-two-pane-width-based.md) | Two-pane layout is width-based (≥840dp) and only on `MainScreen` | ui |
| [0052](0052-metered-warning-direct-tap-only.md) | The metered-connection warning gates only a direct play tap | ui / settings |
| [0053](0053-accessibility-single-state-mapping.md) | One playback-state → spoken-label mapping; merge only the text column | ui / accessibility |
| [0054](0054-curated-stations-read-only.md) | Curated stations can't be edited | data / ui |
