# ADR-0047: The single-alarm SharedPreferences format migrates lazily from three places

Status: Accepted · Area: alarms

**Decision.** `AlarmRepository.migrateLegacyAlarmIfNeeded()` reads `ui/playback/AlarmStateStore.kt` once, inserts a `WakeAlarm` row and clears the store (naturally idempotent). Called from `AlarmListViewModel.loadAlarms()`, `BootReceiver`, and `AlarmReceiver` when a broadcast arrives without `EXTRA_ALARM_ID`.

**Because.** An app update doesn't clear `AlarmManager` registrations (only a reboot does), so an old-format alarm can fire before the user ever opens the alarm screen.
