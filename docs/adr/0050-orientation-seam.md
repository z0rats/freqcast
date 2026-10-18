# ADR-0050: Orientation is a plain `LocalConfiguration` check; only `PlaybackScreen` branches on it

Status: Accepted · Area: ui

**Decision.** `ui/theme/WindowInfo.kt`'s `isLandscape()` is the seam. `PlaybackScreen` alone has a landscape layout: a two-pane `Row` (icon+name left; track title/play/sleep timer right). Other card lists cap at `widthIn(max = 600.dp)`, centered. `AlarmEditScreen`'s time dialog falls back from the dial `TimePicker` to `TimeInput` below `TimePickerDialogDefaults.MinHeightForTimePicker`.

**Because.**
- Mirrors Spotify/Apple Music so nothing scrolls at a phone's landscape height.
- The dial isn't scrollable inside a plain `AlertDialog` and gets clipped in landscape.

**Rejected.**
- A `WindowSizeClass` dependency (ADR-0001).
