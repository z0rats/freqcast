# ADR-0025: Reconnect with capped exponential backoff, keyed by attempt id

Status: Accepted · Area: playback

**Decision.** `PlaybackSession.retryDelayMs`: 2s/4s/8s/16s, capped 30s, `MAX_RETRY_COUNT = 5`. The service owns the `ConnectivityManager.NetworkCallback` and `Handler.postDelayed`; `PlaybackSession` returns `RetryDecision` (`RetryNow`/`RetryAfter`/`GiveUp`/`NoAction`), keyed by a monotonic attempt id.

**Because.** A retry scheduled before a station switch or a fresh play must be a no-op, not fire against the wrong stream.

**Rejected.**
- Infinite retry — *reason not recorded.*
