# ADR-0058: Android Auto station art is an exported station-icon content URI

Status: Superseded by ADR-0061 · Area: playback / Auto

Supersedes ADR-0057.

**Decision.** A station whose `customIcon` is a UUID `.jpg` directly under `filesDir/station_icons/` is exposed as `MediaMetadata.artworkUri` via `IconStorage.contentUri`: `content://<applicationId>.stationicons/icons/<uuid>.jpg`. `StationIconProvider` opens it. The provider is exported, has no read permission, and `openFile` allows only mode `r` for that one shape of path; the canonical file must be a regular file whose parent is `station_icons/`. `query` returns null. Browse items (`RadioBrowseTree`), the playing item (`applyPlayback`), and the ICY title update (which keeps the URI through `buildUpon`) all use it. `RadioPlaybackService` still grants `FLAG_GRANT_READ_URI_PERMISSION` on those URIs only (`IconStorage.isOwnContentUri`) to the browsing controller, connected session controllers, and `com.google.android.projection.gearhead`. An emoji `customIcon` gets no artwork URI. The backup/clip FileProvider stays `exported="false"` and its `file_paths.xml` no longer includes `station_icons/`.

**Because.** (scar) After ADR-0057, Desktop Head Unit's For you shelf still drew the music-note placeholder for stations that have a file icon (curated KURS Radio and SURPRISE.FM are copied into `station_icons/` by `CuratedStations.withResolvedIcon`; the phone draws that file). Media3 1.11 `LegacyConversions` already copies `artworkUri` onto `MediaDescriptionCompat.setIconUri` and onto `METADATA_KEY_DISPLAY_ICON_URI` / `ALBUM_ART_URI` / `ART_URI`, so the URI was on the item. `grantUriPermission` to gearhead does not reach the process that decodes For you card art, and an unexported FileProvider then refuses the open.

**Accepting.** Any app that learns a station-icon URI can read that JPEG. Names are random UUIDs and the bytes are the logos already shown on the phone. Emoji-only stations stay blank on Auto, same boundary as launcher shortcuts (ADR-0044).

**Rejected.**
- Exporting `com.freqcast.fileprovider` — that provider also serves backups and clips.
- Granting more packages — For you's loader is not a package the grant reaches.
- `artworkData` bytes — Auto's browse list does not render them (ADR-0057); Media3 1.11 already forwards the URI, and the failure was opening it.
- A `file://` URI, or rasterizing the emoji into a second artwork file — same rejection as ADR-0057.
