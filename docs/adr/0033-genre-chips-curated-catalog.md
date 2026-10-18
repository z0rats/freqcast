# ADR-0033: GENRE quick-select chips come from a curated, translated catalog

Status: Accepted · Area: discovery

**Decision.** `PopularGenresChips` is built from `util/GenreCatalog.kt` (`Genre(queryTag, labelRes)`), mirroring `CountryCatalog`'s shape: `queryTag` is an English tag (e.g. `"jazz"`) for `RadioBrowserApi.search(_, SearchBy.TAG)`; `labelRes` is the localized label (en/ru/es/zh-rCN).

**Because.** Real-world tags overwhelmingly use English regardless of the station's language, while the label must match the UI language.

**Rejected.**
- The live `/json/tags` top list for the chip row — tried first; it mixes languages/scripts unpredictably and reads as random clutter. (Using the same catalog for *typed* autocomplete is fine — ADR-0043.)
