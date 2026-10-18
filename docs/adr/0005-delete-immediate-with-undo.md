# ADR-0005: Deleting a station is immediate and undoable, not confirmation-gated

Status: Accepted · Area: data / main screen

**Decision.** `MainViewModel.deleteStation()` deletes, reloads and emits `MainScreenEvent.StationDeleted`; `MainScreen` shows a Snackbar with Undo. `undoDelete()` calls `restoreStation()`, which re-inserts the same `RadioStation` with the same `id` (SQLite accepts it as an explicit rowid) and the same `sortOrder`, restoring the original position.

**Rejected.**
- A confirmation dialog before delete — *reason not recorded.*
