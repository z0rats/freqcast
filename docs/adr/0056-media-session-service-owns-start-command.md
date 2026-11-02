# ADR-0056: `MediaSessionService.onStartCommand` is always forwarded; `PlayerNotificationManager` owns the notification

Status: Accepted · Area: playback / Auto

**Decision.** `RadioPlaybackService.onStartCommand` always calls `super.onStartCommand` first, then dispatches `PlaybackCommands`. `onUpdateNotification(session, startInForegroundRequired)` is a no-op so Media3's `MediaNotificationManager` does not post a second notification or `startForegroundService` on every player event.

**Because.** (scar) Media3's `MediaNotificationManager.startForeground` starts the service with a UID extra on each READY/BUFFERING update. Skipping `super` left those intents as "stray" (~1/s in logcat), two notification pipelines raced, the shade flickered, and `NotificationService` shed posts (`Package enqueue rate`). Playback dropped when the foreground promotion lost the fight.

**Rejected.**
- Keep ignoring non-`PlaybackCommands` intents — Media3 never marks its self-start handled and retries forever.
- Drop `PlayerNotificationManager` and use Media3's default notification — larger UI/skip-button rewrite; the existing manager already owns timeshift seek and station skip.

**Supersedes.** ADR-0021's *Revisit* (the mid-playback "stray" intents were Media3 self-starts).
