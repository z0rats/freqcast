# ADR-0045: Wake-up alarms use `setAlarmClock`, one `PendingIntent` per Room row

Status: Accepted · Area: alarms

**Decision.**
- Scope: multiple daily alarms, each enabled/time/station. Not a clock app — no day-of-week, no snooze.
- `ui/AlarmScheduler.kt` uses `AlarmManager.setAlarmClock`. `schedule()` returns `Boolean`: checks `canScheduleExactAlarms()` first, wraps the call in `try/catch(SecurityException)`, never throws. `AlarmEditScreen`'s save reacts to `false` (`alarm_permission_needed` + `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`); `BootReceiver`/`AlarmReceiver` discard it.
- Each alarm is a `data/WakeAlarm` row (schema v9, `MIGRATION_8_9`) via `data/AlarmRepository`, with its own `PendingIntent` request code `AlarmScheduler.requestCodeFor(id)` (offset by 2000).
- `AlarmReceiver` reschedules for tomorrow after firing (that's what makes it daily); `BootReceiver` reschedules every `enabled && streamUrl != null` row.
- `save()` forces `enabled = false` when no station is selected (silent).

**Because.**
- A user-visible alarm clock is documented exempt from `SCHEDULE_EXACT_ALARM`.
- (scar) The exemption isn't guaranteed on all devices; `setAlarmClock()` can still throw `SecurityException`.
- The 2000 offset avoids request-code collisions; one intent per row lets alarms be scheduled/cancelled independently.

**Rejected.**
- `setExactAndAllowWhileIdle` — needs the exact-alarm permission without the alarm-clock exemption.
