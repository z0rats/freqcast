# ADR-0016: Coverage is informational, never a merge gate

Status: Accepted · Area: testing / CI

**Decision.** Kover reports the `debug` variant only. CI uploads the XML to Codecov with `fail_ci_if_error: false`. No `koverVerify` / minimum-bound rule.

**Because.** A Codecov outage or a Kover hang (ADR-0020) must never red out the pipeline; the report is a better signal for whole untested files than for single lines (it misattributes lines inside lambdas passed to suspend/callback APIs).
