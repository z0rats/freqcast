# ADR-0002: `RadioStationRepository` is the only gateway to the station table; ViewModels hold no `Context`

Status: Accepted · Area: cross-cutting

**Decision.** Only `data/RadioStationRepository` touches `RadioStationDao`/`AppDatabase`. ViewModels take a `RadioStationRepository` (created via `RadioStationRepository.create(context)`), never an `AppDatabase` or `Context`. Android-API calls a ViewModel would need (`AlarmScheduler.schedule/cancel`, permission requests) live in the Composable/Activity layer instead.

**Accepting.** One exception: `DiscoverStationsViewModel` holds `appContext` (always `applicationContext`) because Discover's fire-and-forget favicon backfill needs `IconStorage`.

**See also.** Export/import is deliberately *not* in the repository either: `data/StationBackupIO.kt` only calls `insertStation()`.
