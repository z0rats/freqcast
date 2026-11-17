# ADR-0061: Android Auto For you art includes a small JPEG, plus the content URI

Status: Accepted · Area: playback / Auto

Supersedes ADR-0058.

**Decision.** A station whose `customIcon` is a UUID `.jpg` directly under `filesDir/station_icons/` is exposed two ways. `IconStorage.contentUri` is still `content://<applicationId>.stationicons/icons/<uuid>.jpg`, opened by the exported `StationIconProvider` (mode `r` only, canonical file must be that JPEG). `query` returns `OpenableColumns.DISPLAY_NAME` and `SIZE`. `openAssetFile` reports the file length. `IconStorage.artworkJpeg` is the same file, recompressed so the longer side is at most 256px, and is set as `MediaMetadata.artworkData` (`PICTURE_TYPE_FRONT_COVER`) on browse items (`RadioBrowseTree`) and on the playing item (`playbackMetadata`). The ICY title update keeps both through `buildUpon`. `RadioPlaybackService` grants read on the URI to the browsing controller, connected session controllers, `com.google.android.projection.gearhead`, and `com.google.android.googlequicksearchbox`. An emoji `customIcon` gets neither. The backup/clip FileProvider stays unexported and does not serve `station_icons/`.

**Because.** (scar) After ADR-0058 the Desktop Head Unit For you shelf still drew the music-note placeholder for stations whose file icon the phone draws (KURS Radio, SURPRISE.FM, and others such as ROVR). The playing item and the browse items already carried the `stationicons` URI, so this was not a null `artworkUri` and not the old FileProvider URI. Media3 1.11 `MediaLibraryServiceLegacyStub` copies `artworkUri` to `MediaDescriptionCompat.setIconUri` and decodes a bitmap only from `artworkData`. `MediaSessionLegacyStub.updateMetadataIfChanged` copies the URI onto `DISPLAY_ICON_URI` / `ALBUM_ART_URI` / `ART_URI` and loads a bitmap asynchronously; if that load fails, `onFailure` logs and leaves the bitmap null. For you is a shelf of several station cards, filled from browse children (the browser package observed in the wild is `com.google.android.googlequicksearchbox`, not gearhead) and from the session metadata of what was just playing. The URI was on those items and the shelf still used the placeholder. `StationIconProvider.query` also returned null, and `openAssetFile` reported an unknown length. `OpenableColumns` requires display name and size for an openable URI, and `ImageDecoder` / `loadThumbnail` open `image/*` using the declared length — a null cursor or an unknown length is a failed image while `openInputStream` (the unit test) still succeeds.

**Accepting.** Every browse child with a file icon carries a small bitmap. One 256px ARGB bitmap is about 256KB; `ParceledListSlice` starts a new transaction once the parcel passes 64KB, so the list is split rather than sent as one blob. Emoji-only stations stay blank on Auto. The in-app browse list receives the same bitmap; Android Automotive OS ignores `setIconBitmap` and still has the URI. Any app that learns a station-icon URI can read that JPEG.

**Rejected.**

- A 512px `artworkData` — the stored icon's full size. Two copies in session metadata (`DISPLAY_ICON` and `ALBUM_ART`) approach the 1MB binder limit.
- Dropping the content URI — the car docs still want a local URI, and Automotive OS does not render the bitmap.
- Bytes on the playing item only — For you shows a row of stations, which is the browse children, not only the one now playing.
- Leaving `query` null — that is the openable-URI contract, and it matches a loader that never calls `openFile`.
