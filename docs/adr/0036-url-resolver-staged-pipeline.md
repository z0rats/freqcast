# ADR-0036: Everything pasted into Add Station goes through one staged `StationUrlResolver`

Status: Accepted · Area: discovery

**Decision.** `StationUrlResolver.resolve(url, onStage): Resolution` (`Found`/`Ambiguous`/`NotFound(tlsBlocked)`) is the single entry point; `AddStationViewModel.save()` maps it with one `when`. Stages, each only if the previous found nothing:
0. probe the typed URL (`CHECKING_URL`, `StreamValidator.probe()`); only if unreachable / not audio continue into `internal resolveHomepage()`;
1. `fromDirectory` — Radio Browser match on `homepage` host (ADR-0037);
2. `fromHtml` — regex over raw HTML;
3. `fromLinkedScripts` — same over same-origin `<script src>`, plus AzuraCast `/api/nowplaying` / Icecast `/status-json.xsl` probes on same-domain subdomains;
4. `fromIframes` — same over third-party embeds, minus `IFRAME_HOST_BLOCKLIST`;
5. `fromWebView` — headless sniffer (ADR-0038).

Every candidate from any stage passes `StreamValidator.isPlayableStream()` before acceptance.

**Accepting.** A false-positive-shaped string in a linked script can pre-empt the AzuraCast/Icecast probe (`candidates.isEmpty()` gate).
