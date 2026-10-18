# ADR-0018: Screenshot baselines are recorded on Ubuntu only

Status: Accepted · Area: testing / CI

**Decision.** Baselines come from `ubuntu-latest` (CI). Re-record through a temporary `workflow_dispatch` workflow running `./gradlew recordRoborazziDebug`, never locally on macOS. The fix-forward procedure is in `docs/architecture/testing-ci.md`.

**Because.** Font rendering differs by platform enough that `verifyRoborazziDebug` on macOS fails with a full-image diff even with zero relevant changes.

**Accepting.** `./gradlew test` locally runs capture but not verify, so a visual change to a covered Composable only fails in CI.
