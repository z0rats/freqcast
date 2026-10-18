# ADR-0020: `koverXmlReportDebug` is its own CI step with a timeout and `continue-on-error`

Status: Accepted · Area: CI

**Decision.** Separate step, `timeout-minutes: 8`, `continue-on-error: true`.

**Because (scar).** Kover's coverage javaagent stacked on Robolectric's instrumenting classloader has hung `:app:testDebugUnitTest` indefinitely (zero completed test classes for 27+ minutes, confirmed from full job logs). Coverage is informational (ADR-0016), so it must never block the test signal or eat unbounded minutes.

**Rejected.**
- Merging it into the test step (ADR-0019) — a hang would block the real test result.

**Revisit.** Root cause unconfirmed. If it hangs again even with the timeout: try a different Kover coverage engine, or exclude the Robolectric-native-graphics classes (`IconStorageTest`, `StationItemScreenshotTest`) from instrumentation.
