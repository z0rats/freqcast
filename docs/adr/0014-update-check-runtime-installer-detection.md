# ADR-0014: The GitHub update check is skipped by a runtime installer check, not a build flavor

Status: Accepted · Area: release

**Decision.** `SettingsViewModel`'s GitHub `releases/latest` request is skipped when `util/InstallSource.isManagedByStore()` sees a known store/update-manager installer package (F-Droid family, RuStore, Play, OEM stores, Obtainium, ...). New store installer ids go into `InstallSource.STORE_INSTALLERS`.

**Because.** Reproducible-build verification needs the F-Droid and GitHub APKs byte-identical (ADR-0015).

**Rejected.**
- A product flavor or `BuildConfig` flag — produces different APKs per channel, breaking reproducibility.
