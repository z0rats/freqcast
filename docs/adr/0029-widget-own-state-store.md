# ADR-0029: The widget has its own `WidgetStateStore`

Status: Accepted · Area: widget

**Decision.** `ui/playback/WidgetStateStore.kt` is separate from `PlaybackStateStore`. `RadioPlaybackService.updateWidget()` writes it and calls `RadioWidget().updateAll()` at the end of every `refreshSnapshot()`.

**Because.** The contracts differ: `PlaybackStateStore` is "last *started*, cleared on explicit stop" (for ADR-0021); the widget keeps showing the last station with a play affordance even after an explicit stop.

**Rejected.**
- Reusing `PlaybackStateStore` — the widget would go blank after stop, or resume would fire after an explicit stop.
