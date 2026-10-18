# ADR-0048: Backup JSON excludes `sortOrder` and `isFavorite`

Status: Accepted · Area: backup

**Decision.** Shape `{name, streamUrl, customIcon, genre, isHls, radioBrowserUuid}[]` (`data/StationBackupJson.kt`), shared by full export and single-station share. Imports append via normal `insertStation()`. Old backups' `isFavorite` key is ignored; old `"genre"` maps to `description`.

**Because.** `sortOrder` isn't meaningful across devices; `isFavorite` no longer exists (ADR-0007).
