# ADR-0012: The curated pack is seeded once into a fresh install, then is ordinary data

Status: Accepted · Area: data

**Decision.**
- `RadioStation.isCurated` (schema v12, `MIGRATION_11_12`) marks a row from the hardcoded `CuratedStations.pack`; drives the "Curated" pill in `StationCard`.
- `MainViewModel.seedCuratedStationsIfNeeded()` seeds it once, guarded by `SettingsStore.hasSeededCuratedPack`, so it never re-runs after the user deletes some or all of it. Deleting is the only day-to-day way to remove them — no ongoing Settings toggle.
- `SettingsViewModel.restoreCuratedStations(context)` ("Restore curated stations" row) re-inserts missing entries via ADR-0003 (`SKIP` on name collision).
- Each entry's real site icon is bundled under `res/raw/curated_*`, downloaded once at dev time. `CuratedStations.withResolvedIcon()` copies it through `IconStorage` into a normal local icon file before insert, so nothing else (rendering, export, widget, Auto) special-cases curated icons.

**Rejected.**
- Re-seeding on every launch / a persistent "show curated" toggle — would resurrect stations the user deleted.
- Fetching icons over the network at seed time — *reason not recorded.*

**Resolved.** Editing a curated station isn't allowed at all — ADR-0054.
