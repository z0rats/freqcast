# ADR-0022: Per-stream state lives in a pure `PlaybackSession`, not in the service

Status: Accepted · Area: playback

**Decision.** `playback/PlaybackSession` (no Android/ExoPlayer) owns `currentRequest` (survives `onStopped()`, so `getCurrentStationName()` and the widget keep naming the last station), the retry state machine (ADR-0025), and the UI error signals `connectionErrorAt` (never reset; drives a one-shot Toast on change) / `isConnectionBroken` (cleared by the next `onPlaybackStarted`). The service reports events (`onPlaybackStarted`/`onPlaybackError`/`onRecorderError`/`onNetworkAvailable`/`onStopped`) and executes the returned `RetryDecision`. `startPlayback()`/`applyPlayback()` take one `PlaybackRequest` value; a retry replays the whole stored request.

**Because.**
- Testable without the service lifecycle (`PlaybackSessionTest`, Robolectric only to construct `PlaybackException`).
- Replaying the whole request means a retry can't flip-flop the HLS decision.
