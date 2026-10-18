# ADR-0017: `TimeshiftController` returns a `DataSource.Factory`, not a `MediaItem`/`MediaSource`

Status: Accepted · Area: playback / testing

**Decision.** `playback/TimeshiftController` stays a `DataSource.Factory`-returning seam; the service builds `MediaItem`/`MediaSource` itself.

**Because.** Constructing bare media3 model classes (`MediaItem`, `ProgressiveMediaSource`, `PlaybackException`) throws `RuntimeException: not mocked` without Robolectric. Building them inside `TimeshiftController` would force `TimeshiftControllerTest` onto Robolectric.
