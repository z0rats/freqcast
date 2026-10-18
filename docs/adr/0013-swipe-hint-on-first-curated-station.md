# ADR-0013: One-time swipe hint on the first curated station, targeting Delete

Status: Accepted · Area: ui / main screen

**Decision.** `StationItem`'s `playSwipeHint` (guarded by `SettingsStore.hasShownSwipeHint`) plays once on the first curated station: waits ~1.8s, does a full-threshold reveal (not just a peek) of its Share/Delete row (no Edit — ADR-0054), and pulses the Delete button (scale + `glass_accent` ring).

**Because.**
- Without the long delay it would play underneath the first-launch notification-permission dialog.
- Delete is rightmost in the reveal row and the one action a user actually needs to discover there.

**Rejected.**
- A partial peek — *reason not recorded.*
