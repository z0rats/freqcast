# ADR-0042: "Near me" uses plain `LocationManager`, coarse location, one fix

Status: Accepted · Area: discovery

**Decision.** `util/LocationProvider.kt` wraps `android.location.LocationManager`; prefers cached `getLastKnownLocation()` over `requestSingleUpdate()`; never requests continuous updates or persists a coordinate; gates on `ACCESS_COARSE_LOCATION` only; `DEFAULT_TIMEOUT_MS = 20s`. Search radius `NEARBY_RADIUS_METERS` is a hardcoded 50km. Permission flow lives in `DiscoverStationsScreen`; an ON_RESUME observer retries while the last failure was `discover_location_unavailable`.

**Because.**
- City-level precision is enough (deliberate privacy trade-off).
- A cold-start GPS fix realistically takes 15–20s.
- Coming back from Settings with GPS on should "just work".

**Rejected.**
- `FusedLocationProviderClient` — adds a Play Services dependency (ADR-0001).
- 10s timeout — cut searches off prematurely.
