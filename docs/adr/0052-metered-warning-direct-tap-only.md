# ADR-0052: The metered-connection warning gates only a direct play tap

Status: Accepted · Area: ui / settings

**Decision.** `SettingsStore.warnOnMeteredConnection` (default `true`); the check (`ConnectivityManager.isActiveNetworkMetered`) lives in `MainScreen` and gates only the play button on a `StationItem`.

**Because.** The other paths are already-in-progress switches or background starts with no good confirmation surface.

**Rejected.**
- Gating the mini player / full-screen bar, widget, Android Auto, or wake-up alarm.
