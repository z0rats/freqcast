# ADR-0035: A TLS reset under VPN gets its own error, from two signals

Status: Accepted · Area: discovery

**Decision.** `Probe.tlsHandshakeFailed` distinguishes an `SSLException` from a plain unreachable/timeout. `StationUrlResolver.resolve()` folds it into `Resolution.NotFound(tlsBlocked = true)` from (1) the direct probe of the pasted URL and (2) stage 5's `WebViewStreamSniffer.SniffResult.hadTlsFailure`, mapped field-for-field onto `SniffOutcome`. `AddStationViewModel.save()` shows `error_stream_blocked_vpn` instead of `error_stream_unreachable` when `checkVpnActive()` is true — an injectable constructor param defaulting to `util/NetworkState.isVpnActive(context)` (`TRANSPORT_VPN`).

**Because.**
- Observed: at least one RU-hosted station's site resets the handshake specifically for VPN-routed connections.
- Both signals are needed: the homepage can load fine while a *different* host its JS talks to is the one blocked.
- Injecting `checkVpnActive` keeps tests off a Robolectric `ConnectivityManager` shadow.

**Accepting.** Stages 2–4's internal `fetchText()` calls don't carry this signal.

**Rejected.**
- A side-channel `AtomicBoolean` for the sniffer's TLS signal — *reason not recorded*; the typed `SniffOutcome` field replaced it.
