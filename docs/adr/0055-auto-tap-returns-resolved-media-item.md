# ADR-0055: Android Auto taps return the resolved MediaItem; the session player swallows the follow-up `setMediaItems`

Status: Accepted · Area: playback / Auto

**Decision.** `MediaLibrarySessionCallback.onAddMediaItems` calls `resolveBrowseTap()`, which starts the station through `playFromBrowseTree()` / `applyPlayback()` (reloading the browse-tree cache on a miss) and returns that station's playable `MediaItem` (URI + `mediaId` = stream URL). `TimeshiftSeekPlayer` then no-ops the session's follow-up `setMediaItems` / `setMediaItem` when the `mediaId` is the station already playing, one the browse tree can start, or the items have no URI.

**Because.** (scar) Returning an empty list is the Media3 contract for "could not resolve". Android Auto shows "could not load your selection, back to browse" and the session calls `Player.setMediaItems([])`, which tears down the HLS/timeshift source `applyPlayback()` just attached — log line `BUFFERING` then `ENDED` a few tens of ms later (GitHub issue 33). Returning the item without swallowing `setMediaItems` would still replace that source with a plain URI item and drop timeshift / the custom HLS factory. Auto talks to the session through the legacy `playFromMediaId` path, which builds a `MediaItem` with `mediaId` and no URI; handing that to ExoPlayer NPEs in `DefaultMediaSourceFactory`. A tap can also race `onGetChildren`, so the browse-tree cache is reloaded on a miss before giving up.

**Rejected.**
- Keep returning an empty list — Auto treats it as a failed load and the player ends.
- Return the item and let the session `setMediaItems` through — plays the raw URL, skipping timeshift and the HLS `MediaSource`.

**Supersedes.** The "returns an empty list" bullet of ADR-0028.
