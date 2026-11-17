# ADR-0060: A station row tap starts playback and does not pause

Status: Superseded by ADR-0062 · Area: ui / main screen

**Decision.** Tapping a `StationItem` card starts that station through the same `onPlayClick` callback as its play icon, so `MainScreen`'s metered-connection warning still applies (ADR-0052). If `isPlaying` is already true, the card tap does nothing. Pause stays on the play/pause icon and on `NowPlayingBottomBar`. A tap while swipe-reveal is open only closes the reveal. The icon stays a separate click target. TalkBack does not merge the row into one node (ADR-0053): the icon remains its own play/pause stop, and the name+status column exposes an `onClick` labeled with `R.string.play` only when the tap would start playback. The card's own click gets that same label in that same case. Long-press drag-to-reorder is unchanged (ADR-0008).

**Because.** The play icon is a small target; the row is the obvious place to tap. A row that also paused would stop a station the listener tapped to make sure it was the one playing, and would add a third pause control next to the icon and the now-playing bar.

**Accepting.** While a station is playing, tapping its row does nothing.

**Rejected.**
- A play/pause toggle on the row — pause would have three targets, and a tap meant to select the station would stop it.
- Merging the whole row into one TalkBack node — swallows the play/pause button (ADR-0053).
- A drag handle — ADR-0008.
