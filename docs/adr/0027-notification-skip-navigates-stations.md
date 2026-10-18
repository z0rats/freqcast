# ADR-0027: Notification skip buttons navigate the station list

Status: Accepted · Area: playback

**Decision.**
- `TimeshiftSeekPlayer` (the `ForwardingPlayer` wrapper) force-enables `COMMAND_SEEK_TO_NEXT/PREVIOUS` and overrides `seekToNext()`/`seekToPrevious()` to `RadioPlaybackService.switchToAdjacentStation(StationNavigator::next / ::previous)` — the list in ADR-0007 order, wrapping at both ends.
- `setupNotificationManager()` calls the *instance* methods `setUsePreviousActionInCompactView(true)`/`setUseNextActionInCompactView(true)` so the buttons render in the collapsed view too.
- `switchToAdjacentStation` calls `applyPlayback` directly, not `startPlayback` (ADR-0028).
- Calls are serialized by `stationSwitchMutex`.

**Because.**
- The player only ever has one `MediaItem` (the live stream), so ExoPlayer reports next/previous unavailable by default.
- It fires from inside `PlayerNotificationManager`'s click callback, where rebuilding the session isn't safe.
- (scar) `getAllStations()` is a real suspend query: without the mutex a rapid double tap read the same not-yet-updated `currentRequest` twice and dropped a tap (`RadioPlaybackServiceAutoTest`'s two-back-to-back-`previous` assertion).
