# ADR-0024: Playback UI binds through a narrow `PlaybackController` with one pure toggle decision

Status: Accepted · Area: playback / ui

**Decision.**
- `ui/playback/controller/PlaybackController.kt` is all Compose/Activity code sees: `toggle`, `stopPlayback`, seek, sleep timer, clip export, `playbackSnapshot`, `getCurrentStationName()`. `ServiceBackedPlaybackController` is the production adapter, `FakePlaybackController` (test source set) the second one. `MainScreen`/`PlaybackScreen`/`NowPlayingContent`/`ClipExport` take `PlaybackController?`, never `RadioPlaybackService?`.
- `toggle(station): ToggleResult` (STARTED/STOPPED/NETWORK_UNAVAILABLE) is decided by the pure `decideToggleAction()` (`internal`, `PlaybackControllerLogicTest`): compares the station's stream URL with `PlaybackSnapshot.currentMediaId`, and checks "is this station already playing" *before* the network check.
- `rememberPlaybackController()` is the one bind/unbind Composable (`DisposableEffect` + `LifecycleEventObserver` ON_START/ON_STOP). It can write into an external `MutableState<PlaybackController?>` — `PlaybackActivity` passes one because `onResume()`/`onNewIntent()` need the controller synchronously outside Compose.
- `ConnectionErrorToastEffect(connectionErrorAt)` (`ui/components/PlaybackPresentation.kt`) is the one shared "connection failed" Toast, deduped by timestamp.

**Because.**
- Stopping never needs a network.
- The controller must be Compose state, not a plain `var`: `onServiceConnected` fires after first composition.

**Rejected.**
- Three independent toggles (`MainActivity.playStation`, `PlaybackActivity.togglePlayback`, `NowPlayingBottomBar`) — `PlaybackActivity`'s compared only "is *anything* playing", a latent bug that could stop the wrong stream.
- Hand-rolled `ServiceConnection`/`bindService` per Activity, and the Toast `LaunchedEffect` copy-pasted into `MainScreen` and `PlaybackScreen` — duplicated logic that drifted.
