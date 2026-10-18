# ADR-0009: Drag swap-search and auto-scroll run at most once per frame

Status: Accepted · Area: ui / main screen

**Decision.** `onDrag` only accumulates raw movement (`draggedDistance += offset.y`). Swap-target search and auto-scroll live in `settle()`, called once per rendered frame by `rememberDragDropState`'s `while (true) { withFrameNanos {}; state.settle() }`. `settle()` calls `state.scrollBy(overscroll)` directly (it only ever runs inside that `LaunchedEffect`).

**Because (scar).** `onDrag` fires per raw pointer sample, faster than layout catches up. Several calls in one frame each found and applied a swap against the same stale `layoutInfo`, cascading into wrong-pair swaps: a distant station jumping on its own, two cards highlighted, the highlight landing on a different station than the one under the finger (confirmed via screen recording).

**Rejected.**
- Swap search inside `onDrag` — the cascade above.
- Posting auto-scroll through a channel to a separate collector — once both are frame-gated there's nothing left to buffer.

**Consequence for tests.** The infinite per-frame loop hangs `composeTestRule.waitForIdle()`; see `docs/architecture/testing-ci.md`.
