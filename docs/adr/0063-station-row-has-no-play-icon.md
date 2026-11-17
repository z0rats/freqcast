# ADR-0063: Station list rows have no play/pause icon

Status: Accepted · Area: ui / main screen

Supersedes ADR-0062.

**Decision.** A `StationItem` has no play/pause control. Tapping the card toggles that station: `onStopPlayback` when `isPlaying`, otherwise `onStationItemPlayClick` (metered-connection warning, ADR-0052). A tap while swipe-reveal is open only closes the reveal. The visible play/pause control is `NowPlayingBottomBar` (and `PlaybackScreen` when that is open). TalkBack labels the card's click `R.string.pause` or `R.string.play`. `StationCard` still merges only the name+status column (ADR-0053). Long-press drag-to-reorder is unchanged (ADR-0008).

**Because.** The row already toggled (ADR-0062). A second play/pause on every row duplicated the bottom bar and took space from the name.

**Accepting.** Pause from the list is only the row tap until the mini player is showing.

**Rejected.**
- Keep the per-row icon as a second control — ADR-0062; the row tap already did the same thing.
- A row that only starts and never pauses — ADR-0060.
- Merging the whole `StationCard` into one TalkBack node — MiniPlayerCardFull still needs a sibling play/pause stop (ADR-0053); StationCard keeps the same merge as that card.
