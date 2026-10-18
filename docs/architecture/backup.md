# Backup: export / share / import

Station export/import and per-station sharing — only relevant when touching `SettingsScreen`'s import/export or `util/StationShare.kt`. Reasons and rejected alternatives live in the cited ADRs (`docs/adr/`).

- `data/StationBackupIO.kt` owns export/import; the repository (a plain station-table gateway, ADR-0002) only receives `insertStation()` calls from it.
- Backup JSON shape (`data/StationBackupJson.kt`) excludes `sortOrder`/`isFavorite`; old `"genre"` is still read — ADR-0048.
- `StationBackupIO.import()` (via `SettingsViewModel.importStations()`) sniffs content and dispatches to JSON or `data/PlaylistImport.kt` (OPML → M3U/M3U8 → PLS) — ADR-0049. Format details:
  - OPML: XML containing an `<outline>` tag, parsed with `android.util.Xml`'s pull parser — so `PlaylistImportTest` needs `RobolectricTestRunner` even with no `Context`, same as `org.json`.
  - M3U: `#EXTINF:duration,Title` names the URL line that follows; a bare URL line uses itself as the name.
  - PLS: `FileN`/`TitleN` pairs keyed by numeric suffix, order-independent.
  - Same skip-on-duplicate / failed-on-missing-field semantics as JSON import.
- Export and per-station Share (swipe action) both go through `util/StationShare.kt` (`Intent.ACTION_SEND` file attachment, so it reaches Telegram/WhatsApp/email directly) via a `FileProvider` (`res/xml/file_paths.xml`, authority `com.freqcast.fileprovider` hardcoded — keep in sync with `applicationId`). `FileProvider` caches roots per authority for the whole test JVM, so `StationShareTest` is the one FileProvider test, with a single test method. `testOptions.unitTests.includeAndroidResources = true` is required for Robolectric to see `res/xml/file_paths.xml`.
- Track title (ICY metadata via `StreamRecorder`, shown in `PlaybackScreen`) has a copy-to-clipboard button (`LocalClipboardManager`). Long single-line values (station name, track title) scroll via `ui/components/MarqueeText.kt` or `PlaybackScreen`'s `CopyableLabel(marquee = true)` — both pass `basicMarquee(iterations = Int.MAX_VALUE)`; don't inline the modifier (the no-arg default scrolls 3 times then freezes, reading as broken). Notifications can't marquee (`RemoteViews`), but Android 11+'s SystemUI media panel scrolls `MediaMetadata`, so `updateMediaItemMetadataForTrack()` keeps `setArtist(trackTitle)` current.
