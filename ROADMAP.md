# Roadmap / Ideas

## Usefulness (highest impact)

- ~~**Localization (i18n)**~~ — done: Russian, Spanish and Simplified Chinese added (`res/values-ru/`, `res/values-es/`, `res/values-zh-rCN/`). Adding another language is just a new `values-<lang>/strings.xml` with the same keys — see AGENTS.md's Localization section for the process to keep them in sync going forward.

## Technical

- **Wear OS complication/tile** — not a small follow-up to the widget, despite both reading `MediaSession` state: the widget runs in-process on the phone (Glance + direct `Intent`/`ActionCallback` into `RadioPlaybackService`), but a Tile/Complication runs on a separate watch device as its own app. Needs: a new `wear/` Gradle module (own manifest, `TileService`/ProtoLayout deps, `ComplicationDataSourceService`); a phone↔watch comms layer via the Wearable Data Layer API (`MessageClient`/`DataClient`, a new Play Services dependency — this project has so far deliberately avoided Play Services, e.g. `LocationProvider` uses plain `LocationManager` instead of `FusedLocationProviderClient` for that reason); and real verification on a Wear emulator/device, since this project's Robolectric-only test setup doesn't cover ProtoLayout rendering. Multi-day scaffolding effort, not a quick add.
