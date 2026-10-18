# ADR-0026: `startForegroundWithNotification()` can fail and returns `Boolean`

Status: Accepted · Area: playback

**Decision.** Returns `Boolean`; catches plain `Exception`. Both call sites (`applyPlayback`, the `PlayerNotificationManager` listener) treat `false` like `RetryDecision.GiveUp`: call `stopPlayback()` (and `return` in `applyPlayback` before building the player/session pipeline).

**Because (scar).** Android 12+ throws `ForegroundServiceStartNotAllowedException` from `startForeground()` without a valid background-start exemption — observed from both background restart paths: the network-recovery retry and the null-intent process-death resume (ADR-0021).

**Rejected.**
- `catch (ForegroundServiceStartNotAllowedException)` — referencing an API-31-only class in a `catch` can trip ART's eager verifier below minSdk 31 (minSdk is 29).
