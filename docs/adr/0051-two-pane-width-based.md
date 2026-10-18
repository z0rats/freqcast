# ADR-0051: Two-pane layout is width-based (≥840dp) and only on `MainScreen`

Status: Accepted · Area: ui

**Decision.** `isWideScreen()` = `screenWidthDp >= 840` (Material "expanded"). `MainScreen` then shows `StationListPane` (fixed 360dp) | 1dp divider | `NowPlayingContent` (no Scaffold/TopAppBar) as a persistent detail pane, with a "select a station" empty state. The mini player is hidden there. Tapping play drives the same `currentPlayingStationId`/`onPlayStation` flow but renders in the pane instead of pushing `PlaybackActivity`. Other screens stay single-pane, 600dp cap.

**Because.** A folded phone in landscape and an unfolded tablet both report landscape, but only the latter has 840dp+; the detail pane already covers the mini player's role.

**Rejected.**
- Orientation-based two-pane — triggers on landscape phones without the width.
