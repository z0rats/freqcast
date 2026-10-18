# ADR-0044: Two dynamic App Shortcuts into an exported `PlaybackActivity`

Status: Accepted · Area: shortcuts

**Decision.** `util/AppShortcuts.kt` publishes ≤2 dynamic shortcuts: last-played station (`PlaybackStateStore`) and first in list order, deduped. Always the auto-generated emoji, never `customIcon`. `AppShortcuts.refresh()` runs from `LaunchedEffect(allStations)` in `MainScreen`. Intents target `PlaybackActivity` with `EXTRA_AUTO_PLAY = true`, so `PlaybackActivity` is `android:exported="true"`.

**Accepting.**
- A shortcut can lag until the user reopens `MainActivity` (no dedicated observer).
- Any installed app can launch `PlaybackActivity` with an arbitrary `EXTRA_STATION_NAME`/`EXTRA_STREAM_URL` and start playback of an arbitrary URL — low severity (no data access or permission escalation).

**Rejected.**
- Gating `PlaybackActivity` behind a signature permission — not worth it for the severity above.
