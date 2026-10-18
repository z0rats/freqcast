# UI layout & presentation conventions

Station icons, theming/dimens, orientation/window-size branching, drag-to-reorder and other Compose-layer conventions — only relevant when touching layout/theming/list code. Reasons and rejected alternatives live in the cited ADRs (`docs/adr/`).

## Station list: drag-to-reorder

```
long-press on any card (whole LazyColumn is the detector; disabled while searchQuery is non-blank)
  onDragStart  → draggingItemKey = station id, draggingItemInitialOffset captured once
  onDrag       → draggedDistance += offset.y            (nothing else)
  every frame  → settle(): find swap target by leading edge → onMove → MainViewModel.moveStation() (in-memory)
                           auto-scroll via state.scrollBy(overscroll)
  onDragEnd    → MainViewModel.persistStationOrder() → updateSortOrder(orderedIds) (@Transaction)
render: isDragging = station.id == draggingItemKey → elevated Card/border, graphicsLayer { translationY }, zIndex(1f)
```

- `ui/components/DragDropState.kt`, hand-rolled — ADR-0008.
- `settle()` at most once per frame — ADR-0009.
- Dragged item found by key, never index — ADR-0010.
- Leading-edge swap test — ADR-0011.
- Order source of truth (`sortOrder ASC, id ASC`) — ADR-0007.

## Station icons

- `RadioStation.customIcon` = emoji or image file path (leading `/` is `IconStorage.isImagePath()`'s discriminator; every render site falls back to the emoji if the file is missing). Default: `util/EmojiGenerator`. Override via `ui/components/StationIconPickerDialog.kt` (emoji, or photo picker → `IconStorage.saveImage()` ≤512px JPEG in `filesDir/station_icons/`). Shown in `StationCard`, `PlaybackScreen`'s `StationArt`, the notification large icon (`getCurrentLargeIcon()`); not in the mini-player cards (width goes to the name). `AddStationViewModel` deletes only the *replaced* file, only once `save()` commits — no cleanup on cancel (accepted orphan leak). Directory favicons backfill async (discovery.md); curated icons are bundled (ADR-0012).

## Theming & dimens

- `ui/theme/Dimens.kt`: `Spacing` (4/8/16/24dp — use only where a value already matches exactly) and `AppShapes` (`extraSmall`=8dp icon controls, `medium`=16dp buttons/fields/chips, `large`=24dp primary cards, `extraLarge`=40dp pill mini-player cards), wired into `MaterialTheme(shapes = AppShapes)`. Reference `MaterialTheme.shapes.large`/`Spacing.md`, not new `RoundedCornerShape(Ndp)`/raw `.dp` literals.
- `FreqcastTheme` is always dark: no system-theme following, no light `ColorScheme`, no `dynamicColor`. Default Material components without explicit `colors` are only correct on dark — don't reintroduce `isSystemInDarkTheme()`.
- Every `Switch` uses the one shared `SwitchDefaults.colors(...)` — Material3's default checked track (`md_theme_dark_primary`) is nearly `glass_accent`, hiding the thumb.
- A rounded `shape` + `.clickable()`/ripple: clip before the click handler, or ripple paints the rectangular bounds past the corners. Prefer `Card(onClick = ..., border = ...)`; for plain `Box`/`Row`, `.clip(shape).clickable(...)`.

## Orientation & window size

- `ui/theme/WindowInfo.kt`: `isLandscape()` — ADR-0050 (only `PlaybackScreen` branches; 600dp card-list cap; `TimeInput` fallback). `NowPlayingBottomBar` is a fixed 80dp in either orientation.
- `isWideScreen()` (≥840dp) two-pane `MainScreen` — ADR-0051. `StationListPane` holds the Discover chip/overflow-menu row, search field and list/empty state; `NowPlayingContent` is `PlaybackScreen`'s content without `Scaffold`/`TopAppBar`/back button.

## Main screen wiring

- `StationListActions` (`MainScreen.kt`) bundles the 8 list callbacks; `MainActivity` builds a partial instance and `MainScreen` `.copy()`s in `onStationShareClick`/`onStationItemPlayClick` (they need Compose state). `MainScreen`'s own signature takes only `onAddStationClick`, `onPlayStation`, `onNowPlayingClick`, `playbackController`, `onResume`, plus `stationListActions`.
- Metered-connection warning on direct play tap only — ADR-0052. **Settings screen** (`SettingsActivity`): General (language picker, metered warning, timeshift buffer size, "Disable battery optimization" via `util/BatteryOptimization.kt`), Backup (Export/Import — backup.md; "Restore curated stations" — ADR-0012), Support (feedback email/GitHub, version + update check — ADR-0014).
- Swipe-to-reveal hint on the first curated station — ADR-0013.

## Accessibility (TalkBack)

- One `PlaybackStatus` → spoken-label mapping, live-region track title, merge only the name+status column — ADR-0053.
