# ADR-0053: One playback-state → spoken-label mapping; merge only the text column

Status: Accepted · Area: ui / accessibility

**Decision.**
- `playbackStateDescriptionRes(PlaybackStatus)` (`ui/components/PlaybackPresentation.kt`) is the only mapping, reused by `PlaybackScreen`'s transport button (`stateDescription` over its Play/Pause `contentDescription`) and `NowPlayingBottomBar`'s status text.
- `TrackTitleRow` is `liveRegion = Polite`.
- `StationCard` and `MiniPlayerCardFull` wrap only the name+status `Column` in `semantics(mergeDescendants = true)`.

**Because.**
- The two surfaces can't drift into different wording for the same state.
- An ICY track change is announced without re-focusing.
- TalkBack reads name+status as one stop while the play/pause button stays separately focusable.

**Rejected.**
- Merging the whole row — swallows the button into the merged node's actions.
