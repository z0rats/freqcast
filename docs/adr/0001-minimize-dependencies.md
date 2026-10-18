# ADR-0001: Hand-roll small pieces instead of adding a dependency

Status: Accepted · Area: cross-cutting

**Decision.** Prefer a small in-house implementation over pulling in a library. No DI framework: components resolve `RadioStationRepository.create(context)` themselves.

**Because.** A dependency is only worth it when the in-house cost/risk clearly outweighs a small hand-rolled implementation; for the pieces below it didn't.

**Examples.** `LocationProvider` (ADR-0042), `RadioBrowserApi` (own `OkHttpClient` + `org.json`), `DragDropState` (ADR-0008), `isLandscape()`/`isWideScreen()` (ADR-0050, ADR-0051).

**Rejected.**
- Retrofit / a DI framework for `RadioBrowserApi` and component wiring — *reason not recorded beyond the rule above.*
- `FusedLocationProviderClient` — adds a Play Services dependency (ADR-0042).
- A reorder library for `LazyColumn` (ADR-0008).
