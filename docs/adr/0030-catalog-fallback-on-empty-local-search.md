# ADR-0030: An empty main-screen search falls back to the directory, inline

Status: Accepted · Area: discovery / main screen

**Decision.** When the local search finds nothing for a query of ≥3 characters, `CatalogFallbackSearch` (owned by `MainViewModel`) runs `RadioBrowserApi.search(_, SearchBy.NAME)` (debounce 400ms, `CATALOG_SEARCH_DEBOUNCE_MS`; ≤6 results, `CATALOG_RESULT_LIMIT`) and shows results below "no matches" using the shared `ui/components/RadioBrowserResultCard.kt`. `addStationFromCatalog()` mirrors `DiscoverStationsViewModel.addStation()` field-for-field, favicon included. A network failure is silent: the section just doesn't appear.

**Because.** The local search already succeeded, just with nothing to show; an extra error would be noise.
