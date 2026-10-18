# ADR-0031: Discover never opens on a blank prompt

Status: Accepted · Area: discovery

**Decision.** `DiscoverStationsViewModel.loadDefaultBrowse()` fires once in `init`; `DiscoverResultsContent` shows its list (`defaultBrowseResults`/`defaultBrowseRegionCode`) whenever `!hasSearched`, in any mode including GENRE. Region selection: ADR-0034. Gated by the `autoLoadDefaultBrowse` constructor param (default `true`).

**Because.** The param is `false` in `DiscoverStationsViewModelTest.createViewModel()` so the many exact-`server.requestCount` assertions don't have to account for this unconditional call.

**Rejected.**
- The bare `discover_prompt` text — *reason not recorded.*
