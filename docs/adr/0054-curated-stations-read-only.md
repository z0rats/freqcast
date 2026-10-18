# ADR-0054: Curated stations can't be edited

Status: Accepted · Area: data / ui

**Decision.** A station with `isCurated = true` shows no Edit action in `StationItem`'s swipe-reveal row: only Share and Delete (reveal width 120dp instead of 184dp). With no way to open the edit form for it, `isCurated` never changes after insert. The web demo (`docs/demo/player.js`) mirrors this.

**Because.** A curated station is the developer's pick with a known-good name, stream URL and bundled icon. Once the user edits it, it's no longer that pick, so it doesn't make sense to keep calling it "Curated" — and it's equally odd to quietly drop the pill. Not allowing the edit removes the question.

**Accepting.** To change a curated station the user deletes it and adds their own version; "Restore curated stations" brings the original back (ADR-0012).

**Rejected.**
- Allowing the edit and keeping `isCurated` — that's what `withFormEdits()` (ADR-0004) did by accident: an edited station still showed "Curated".
- Allowing the edit and clearing `isCurated` — the station quietly stops being curated, which is the original pre-ADR-0004 intent; rejected because editing a curated station shouldn't be possible in the first place.

**Supersedes.** ADR-0012's *Revisit* question.
