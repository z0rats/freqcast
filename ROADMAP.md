# Roadmap / Ideas

## Usefulness (highest impact)

- **Localization (i18n)** — currently English-only (`res/values/strings.xml`, no `values-<lang>/` variants at all). Straightforward in scope (translate `strings.xml`), no architecture changes.
- **Multiple wake-up alarms** — `AlarmScreen`/`AlarmStateStore` deliberately support only a single daily alarm today (see CLAUDE.md). Moving to a list would mean an `AlarmStateStore` → Room table migration plus a list UI instead of one form.

## Usability

- **Mobile data warning** — warn before starting playback on a metered connection, so users don't burn mobile data unintentionally.
- **Station favicon as auto-icon**: Radio Browser's search results include a `favicon` URL (a station's real logo) that we don't fetch at all — could feed the existing custom-icon pipeline (`IconStorage`) as another auto-fill source alongside the emoji generator, instead of only ever showing a generated emoji for Discover-added stations.

## Technical

- **Wear OS complication/tile** — same `MediaSession` foundation the home screen widget uses; likely a similar-shaped follow-up now that the widget exists.
- **"Register click" with Radio Browser** on play for stations added via Discover (`GET /json/url/{uuid}`) — the directory uses this to rank stations by popularity; we already sort search results by `clickcount` server-side but never contribute to it.
