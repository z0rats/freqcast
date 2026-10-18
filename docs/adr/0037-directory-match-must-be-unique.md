# ADR-0037: A directory homepage match must be unique; ambiguity asks the user

Status: Accepted · Area: discovery

**Decision.** `fromDirectory` requires the exact-host match to be unique among up to 50 name-keyword candidates. Several matches → `DirectoryResult.Ambiguous` → `Resolution.Ambiguous`, which stops the pipeline and shows `ui/components/CandidateStationPickerSheet.kt`; the pick resolves via `resolveCandidate()` and saves via `AddStationViewModel.selectCandidate()`. A single match whose stream is unplayable is `NoMatch` and falls through to scraping.

**Because.** Radio Browser is community data; regional affiliates of one network commonly list the same parent-brand homepage.

**Rejected.**
- Guessing one of the matches, or falling through to a scraped guess — might land on a third, unrelated station.
