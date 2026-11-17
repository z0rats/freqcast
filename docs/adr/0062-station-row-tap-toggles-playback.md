# ADR-0062: A station row tap toggles playback

Status: Superseded by ADR-0063 · Area: ui / main screen

Supersedes ADR-0060.

**Decision.** Tapping a `StationItem` card calls the same `onPlayClick` as its play/pause icon: `onStopPlayback` when `isPlaying`, otherwise `onStationItemPlayClick` (metered-connection warning, ADR-0052). A tap while swipe-reveal is open only closes the reveal. The icon stays a separate click target. TalkBack does not merge the row (ADR-0053): the icon remains its own stop, and the name+status column's `onClick` is labeled `R.string.pause` when the tap would pause and `R.string.play` when it would start. The card's click gets the same label. Long-press drag-to-reorder is unchanged (ADR-0008).

**Because.** (scar) ADR-0060 shipped a row that only started playback. Tapping the playing row to pause was the gesture people used, and a row that did nothing felt stuck.

**Accepting.** Pause has three targets: the row, the play/pause icon, and the now-playing bar.

**Rejected.**
- A row that only starts and never pauses — ADR-0060; tried, and the missing pause was the complaint.
- Merging the whole row into one TalkBack node — swallows the play/pause button (ADR-0053).
- A drag handle — ADR-0008.
