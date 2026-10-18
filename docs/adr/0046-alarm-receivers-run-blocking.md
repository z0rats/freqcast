# ADR-0046: Alarm receivers do their Room work with `runBlocking`

Status: Accepted · Area: alarms

**Decision.** `AlarmReceiver`/`BootReceiver` use `runBlocking`, not `goAsync()`.

**Because.** The work is small and bounded, and `runBlocking` keeps them directly testable via a plain `onReceive()` call.

**Accepting.** Their tests hit the real `AppDatabase.getDatabase()` singleton (no injection seam in a `BroadcastReceiver`) and must clear alarms in `@Before`.
