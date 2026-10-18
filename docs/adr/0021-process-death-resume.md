# ADR-0021: Resume after process death from `PlaybackStateStore`, only when the player is idle

Status: Accepted · Area: playback

**Decision.** `playback/PlaybackStateStore` (SharedPreferences) holds the last-*started* station, cleared on explicit stop. When the system restarts the service with a null intent, `onStartCommand` resumes that station — but only if `player.playbackState == Player.STATE_IDLE` (true right after a fresh `onCreate()`). A null/extra-less intent while playback is active is logged and ignored. Resume uses `PlayOrigin.RESUME` (no directory click, ADR-0023).

**Because.** Tearing down and re-requesting an already-buffering/playing stream on a stray intent interrupts playback.

**Revisit.** What actually delivers a null intent mid-playback is unconfirmed (every `PlaybackCommands.start` carries a stream URL).
