# ADR-0019: CI runs `test` and `verifyRoborazziDebug` in one `./gradlew` invocation

Status: Accepted · Area: CI

**Decision.** One invocation for both. If per-step failure attribution is ever needed, use `run: |` with distinct commands — not separate `./gradlew` calls.

**Because.** Both depend on `:app:testDebugUnitTest`. Two invocations configure its inputs differently (Roborazzi verify-mode system properties), so Gradle reruns the whole Robolectric suite twice: 3m26s vs 38s for the same suite.

**Rejected.**
- Separate steps for readability — doubles the suite runtime.
