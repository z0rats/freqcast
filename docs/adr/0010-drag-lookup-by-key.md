# ADR-0010: The dragged item is identified by key, never by index

Status: Accepted · Area: ui / main screen

**Decision.**
- `draggingItemLayoutInfo` looks the dragged item up by its stable `LazyListItemInfo.key` (station id); `draggingItemInitialOffset` is captured once at `onDragStart` and never touched again.
- `MainScreen`'s per-item `isDragging` is `station.id == dragDropState.draggingItemKey`.

**Because (scar).**
- In a stale (pre-recomposition) snapshot an index resolves to *whichever item currently occupies that slot*, not the dragged one.
- `stations` (the `itemsIndexed` index source) arrives via `MainViewModel.filteredStations` (`combine()` → `collectAsState()`), which can land a frame behind `draggingItemIndex` (plain Compose state written in `settle()`), briefly highlighting the wrong station right after a swap.

**Rejected.**
- `index == dragDropState.draggingItemIndex` for `isDragging` and for the layout-info lookup — the two bugs above.
