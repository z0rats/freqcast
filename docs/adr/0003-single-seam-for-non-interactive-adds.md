# ADR-0003: One seam for adding a station without per-field validation

Status: Accepted · Area: data

**Decision.** `RadioStationRepository.insertStationIfAbsent(station, onNameCollision)` is the single path for every add that doesn't show field errors to the user: `RadioBrowserStationInstaller`, `MainViewModel.seedCuratedStationsIfNeeded()`, `SettingsViewModel.restoreCuratedStations()`. A url collision always means "already have it" → skip. A name collision with a *different* station is resolved by `NameCollisionPolicy`: `RENAME` (`"Station (2)"`) for directory adds, `SKIP` for the curated pack.

**Accepting.** `AddStationViewModel.finalizeSave()` is the deliberate exception: it must surface name vs. url collision as distinct field errors, not silently rename or skip.

**Rejected.**
- A new call site with its own inline `isNameTaken`/`isUrlTaken` check — collision policy would drift per call site.
