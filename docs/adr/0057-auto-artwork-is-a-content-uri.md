# ADR-0057: Android Auto station art is a FileProvider `content://` URI

Status: Superseded by ADR-0058 · Area: playback / Auto

**Decision.** A station whose `customIcon` is a file under `filesDir/station_icons/` is exposed as `MediaMetadata.artworkUri` via the existing `com.freqcast.fileprovider` (`IconStorage.contentUri`). Browse items (`RadioBrowseTree`), the playing item (`applyPlayback`), and the ICY title update (which keeps the URI through `buildUpon`) all use it. `RadioPlaybackService` grants `FLAG_GRANT_READ_URI_PERMISSION` on those URIs only (`IconStorage.isOwnContentUri`) to the browsing controller, to every connected session controller, and to `com.google.android.projection.gearhead`. An emoji `customIcon` gets no artwork URI.

**Because.** (scar) Android Auto's station slot stayed empty for every station (GitHub issue 41). The phone draws `customIcon` itself (list, player, notification bitmap). Auto loads artwork only from a `content://` URI in another process, and `file_paths.xml` did not include `station_icons/`.

**Accepting.** Emoji-only stations stay blank on Auto, same boundary as launcher shortcuts (ADR-0044). `IconStorage.contentUri`'s successful `FileProvider.getUriForFile` call stays untested so `StationShareTest` remains the suite's one FileProvider test.

**Rejected.**
- `artworkData` bytes — Auto's browse list does not render them.
- A `file://` URI to `filesDir` — the head unit cannot read app-private files.
- Rasterizing the emoji into a cache file — a second artwork pipeline for a case the phone already draws as text, and another FileProvider root.
