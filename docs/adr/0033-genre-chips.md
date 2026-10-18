# ADR-0033: GENRE chips: a curated translated catalog, one scrolling row in the header, searching without touching the query

Status: Accepted · Area: discovery

**Decision.**
- `PopularGenresChips` is built from `util/GenreCatalog.kt` (`Genre(queryTag, labelRes)`, same shape as `CountryCatalog`): `queryTag` is an English tag (e.g. `"jazz"`) for `RadioBrowserApi.search(_, SearchBy.TAG)`; `labelRes` is the localized label (en/ru/es/zh-rCN).
- It lives in `DiscoverStationsScreen`'s persistent per-mode header (next to `CountryFlagPicker`/the search `TextField`), not in `DiscoverResultsContent`, as a single horizontally scrolling row with no header label — the pill-bar shape of `CountryFlagRow` and Spotify/Apple Music/YouTube Music.
- A tap calls `DiscoverStationsViewModel.searchGenre(tag)`: the `runSearch()` pipeline without changing `uiState.query` (same shape as `searchNearby()` and the COUNTRY flag picker). `uiState.selectedGenreTag` tracks the highlighted chip; `onQueryChange`/`onModeChange` clear it.

**Because.**
- Real-world tags overwhelmingly use English regardless of the station's language, while the label must match the UI language.
- In the header it stays visible after a pick, so the user can switch genre without clearing results.

**Rejected.**
- The live `/json/tags` top list for the chip row — tried first; it mixes languages/scripts unpredictably and reads as random clutter. (Using the same catalog for *typed* autocomplete is fine — ADR-0043.)
- A multi-row wrap — tried first; in the persistent header it ate several rows of vertical space on every genre search.
- `onQueryChange(queryTag)` on tap — echoes the English `queryTag` into the `TextField`, which reads as wrong under a chip labeled in the user's language.
