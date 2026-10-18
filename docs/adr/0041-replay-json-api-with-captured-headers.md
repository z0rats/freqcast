# ADR-0041: A captured JSON API request is replayed with its captured headers

Status: Accepted · Area: discovery

**Decision.** Each capture is a `CapturedRequest(url, headers)` (from `request.requestHeaders`). If a ranked candidate isn't itself playable, `StationUrlResolver.fromJsonApiResponse()` replays it via plain OkHttp with those headers (`apikey`/`Authorization`) and re-runs `extractCandidates()`/`rank()` on the body (`STREAM_KEY_REGEX` matches inside JSON unchanged). The same body is checked for `logo_url`/`favicon_url` (`JSON_ICON_KEY_REGEX`) → `ResolvedStation.favicon`.

**Because.** The captured request is often a backend API (Supabase/PostgREST), not the stream; it needs its credentials to replay. Confirmed real shape (surprise.fm): the `station_settings` row naming `stream_url` also names `logo_url`, and the static-HTML `<link rel="icon">` backfill never sees this JSON — an SPA would otherwise resolve with no icon.

**Rejected.**
- Capturing bare URLs — replay without credentials fails.
