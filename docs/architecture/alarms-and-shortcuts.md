# App Shortcuts & wake-up alarms

Launcher-icon shortcuts and the multi-alarm wake-up feature — only relevant when touching `util/AppShortcuts.kt` or `ui/AlarmListScreen.kt`/`ui/AlarmEditScreen.kt`. Reasons and rejected alternatives live in the cited ADRs (`docs/adr/`).

## App Shortcuts

- `util/AppShortcuts.kt`: ≤2 dynamic shortcuts (last-played + first in list order), refreshed from `MainScreen`, targeting an exported `PlaybackActivity` with `EXTRA_AUTO_PLAY = true` — ADR-0044 (incl. the accepted lag and the accepted "any app can start playback of a URL" risk).

## Wake-up alarms

- **Shape**: `WakeAlarm` Room row (schema v9) → `data/AlarmRepository` (`create(context)`, wraps `WakeAlarmDao`) → `ui/AlarmScheduler.kt` (`setAlarmClock`, one `PendingIntent` per row, `schedule()` returns `Boolean`) → `AlarmReceiver` (plays, reschedules for tomorrow) / `BootReceiver` (reschedules all enabled rows after reboot). ADR-0045.
- Receivers do Room work with `runBlocking` — ADR-0046.
- Legacy single-alarm `ui/playback/AlarmStateStore.kt` migrates lazily from three call sites — ADR-0047.
- UI mirrors the station list/edit split: `AlarmListViewModel` + `AlarmListScreen` (Switch, delete with confirm dialog, FAB to add), `AlarmEditViewModel` + `AlarmEditScreen` (`AlarmEditActivity`'s `EXTRA_ALARM_ID`, `-1`/absent = new). `AlarmScheduler.schedule`/`cancel` are called from the Composable/Activity layer, not ViewModels (ADR-0002). Delete stays available even if the alarm's station was removed from the library.
- `AlarmScheduler.nextTriggerMillis()` is `internal`, covered by `AlarmSchedulerTest`'s pure-function tests.
- **Testing**: `AlarmReceiverTest`/`BootReceiverTest` hit the real `AppDatabase.getDatabase()` singleton (no injection seam in a `BroadcastReceiver`) — `@Before` deletes all alarms to avoid cross-test leakage.
