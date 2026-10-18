# ADR-0008: Hand-rolled long-press drag on the whole `LazyColumn`

Status: Accepted · Area: ui / main screen

**Decision.** `ui/components/DragDropState.kt` uses `detectDragGesturesAfterLongPress`, attached to the whole `LazyColumn`; the whole card is the drag target (no handle icon). `MainViewModel.moveStation()` mutates only the in-memory list per frame; `persistStationOrder()` writes once via `updateSortOrder(orderedIds)` (`@Transaction`) when the drag ends. Drag is disabled while `searchQuery` is non-blank.

**Because.**
- This Compose BOM has no built-in `LazyColumn` reorder support (and see ADR-0001).
- Long-press-then-still naturally disambiguates from `StationItem`'s per-item `detectHorizontalDragGestures` swipe-to-reveal (immediate directional drag).
- Reordering a filtered subset is ambiguous.

**Reference.** Adapted from [aclassen/ComposeReorderable](https://github.com/aclassen/ComposeReorderable).

**Rejected.**
- A reorder library — ADR-0001.
- A separate drag-handle icon — *reason not recorded.*
