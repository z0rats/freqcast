# ADR-0004: The edit path copies form fields onto the loaded station

Status: Accepted · Area: data

**Decision.** `repository.updateStation()` stays a full-row `@Update`, but `AddStationViewModel.finalizeSave()` never rebuilds a `RadioStation(...)` from scratch: `RadioStation.withFormEdits()` (`data/RadioStation.kt`) `copy()`s only the form's editable fields onto the already-loaded row. Fields the form doesn't own (`sortOrder`, `isCurated`, ...) survive an edit by construction, not by being threaded through `AddStationUiState`.

**Because (scar).** A from-scratch rebuild once silently wiped `isFavorite` (since removed) on every edit.

**Rejected.**
- From-scratch `RadioStation(...)` rebuild in the edit path — any field missing from the form gets reset.
- Threading every non-form field through `AddStationUiState` — breaks again the next time a column is added.

**Note.** Curated stations never reach this path — they can't be edited (ADR-0054).
