# Roadmap / Ideas

## Usefulness (highest impact)

- **Alarm ("wake up to radio")** — mirror image of the sleep timer, which already exists; most of the scheduling plumbing would be reusable.
- **Home screen widget** (play/pause, current station name, next/prev) — near-mandatory for a radio app; `RadioPlaybackService`/`playbackSnapshot` already expose everything a widget would need.
- **App Shortcuts** (long-press launcher icon → play last/favorite station directly).
- **Android Auto** — `RadioPlaybackService` is already a `MediaSessionService` with a `MediaSession`/`MediaLibraryService`-shaped foundation, so the playback side is mostly there; the remaining work is a `MediaLibraryService` browse tree (station list as browsable media items) so stations show up and are playable from the car head unit.

## Usability


## Technical


