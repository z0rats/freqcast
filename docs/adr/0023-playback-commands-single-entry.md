# ADR-0023: Every unbound caller starts/stops playback through `PlaybackCommands`

Status: Accepted · Area: playback

**Decision.**
- Activities before binding, the widget, alarms, AppFunctions and `ServiceBackedPlaybackController` call `playback/PlaybackCommands.start(context, station | name+url)` / `.stop(context)`; the service decodes with `PlaybackCommands.parse(intent)`. Extras/action strings are private to that file.
- Inside the service every play goes through `play(request, station, PlayOrigin)`: `START_COMMAND` (rebuild session, count click), `RESUME` (rebuild, no click), `IN_SESSION` (browse-tree tap / notification skip: no session rebuild, count click).
- `RadioBrowserApi.registerClick(uuid)` is fire-and-forget and swallows `IOException`.

**Because.** `RESUME` skips the click so a cold-start process restart doesn't double-count a play in the directory's `clickcount`.
