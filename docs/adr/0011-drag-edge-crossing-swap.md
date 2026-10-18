# ADR-0011: Swap when the dragged card's leading edge crosses the neighbor's edge

Status: Accepted · Area: ui / main screen

**Decision.** `settle()` swaps up into `currentIndex - 1` when `startOffset < item.offset`, down into `currentIndex + 1` when `endOffset > item.offset + item.size`.

**Because (scar).** A midpoint-inside-neighbor check is symmetric and has no memory of the last swap direction: a finger held still on the boundary lets a couple of pixels of jitter toggle it every frame — the top two items flip-flopped every 2–3 frames when dragging into the first slot (screen recording). Edge crossing needs an equally large move back to reverse — the same hysteresis as ComposeReorderable's `chooseDropItem()`.

**Rejected.**
- Shared-midpoint check — jitter flip-flop above.
- Gating the search on `draggingItemLayoutInfo`'s index catching up with `draggingItemIndex` (blaming `filteredStations` lag) — tried, measurably changed nothing.
