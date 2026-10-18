# ADR-0049: Import detects the format from file content, not extension or MIME

Status: Accepted · Area: backup

**Decision.** `StationBackupIO.import()` is the single entry point: content starting with `[` → `importJson()`, else `importPlaylist()` → `data/PlaylistImport.kt`, which tries OPML (XML with `<outline>`, via `android.util.Xml`), `#EXTM3U` M3U/M3U8, then `[playlist]` PLS; throws `IllegalArgumentException` if none match. SAF picker uses `OpenDocument` with MIME filter `*/*`. Playlist formats only carry `name`/`streamUrl`; other fields stay at entity defaults.

**Because.** None of the four formats has a reliable SAF MIME type via `content://` URIs.
