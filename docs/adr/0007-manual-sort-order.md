# ADR-0007: A free manual `sortOrder` replaces favorites/pinning

Status: Accepted · Area: data

**Decision.** `RadioStation.sortOrder` (`Int` ascending, schema v8) plus drag-to-reorder. `RadioStationDao.getAllStations()` orders `sortOrder ASC, id ASC` and is the single source of truth for the list, export, the Android Auto browse tree and the widget/notification skip buttons. `insertStation()` appends (`getMaxSortOrder() + 1`, overwriting the passed value); `restoreStation()` (undo, ADR-0005) inserts as-is, preserving `id`/`sortOrder`.

**Because.** A free manual order and auto-pin-to-top can't coexist.

**Rejected.**
- The old `isFavorite DESC, id ASC` favorites/pinning ordering.
