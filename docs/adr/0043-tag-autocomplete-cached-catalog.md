# ADR-0043: GENRE autocomplete uses a local cache of the full `/json/tags` catalog

Status: Accepted · Area: discovery

**Decision.** `TagSuggestionsList` replaces `PopularGenresChips` in the same header slot when typed text matches cached tags. Backed by `RadioTagRepository`/`RadioTagDao` (`radio_tags`, schema v11, ~11.9k rows). `RadioBrowserApi.fetchTags()` passes an explicit `limit` (50000). `syncTagsIfNeeded()` runs once per cold start and re-syncs when the cached row count is below `MIN_HEALTHY_TAG_COUNT` (2000). `searchByPrefix()` runs on every keystroke, no debounce. Picking a suggestion fills the query and calls `searchGenre()`.

**Because.**
- The language-mixing objection (ADR-0033) only applies to the curated chip row, not to suggesting real matches once the user is typing.
- (scar) A bare `/json/tags` silently truncates to the server default 1000 rows, alphabetically first — mostly punctuation-prefixed junk, not even "jazz" (confirmed live).
- A count threshold, not "non-empty", so a truncated cache from a bad fetch re-syncs instead of being treated as permanently synced.
- A pure local read needs no debounce.
