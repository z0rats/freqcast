# ADR-0015: F-Droid ships our own signed APK via reproducible builds

Status: Accepted · Area: release

**Decision.** `gitlab.com/fdroid/fdroiddata`'s `metadata/com.freqcast.yml` uses `Binaries:` + `AllowedAPKSigningKeys:`; F-Droid verifies its clean-clone `assembleRelease` against the published GitHub Release APK (`apksigcopier compare --unsigned`). `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` lets F-Droid's bot pick up new `v*` tags on its own.

**Accepting.**
- F-Droid's trust is pinned to the current key in `keystore/release.keystore` (gitignored; kept in a password manager). Switching keys later breaks updates for existing F-Droid users; losing the key means future releases can't use it.
- F-Droid's bot writes no changelog: every version bump must commit `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (≤500 chars), or that release silently shows no "what's new".
- Everything that would make channel builds differ is off the table (ADR-0014; also AGP's `dependenciesInfo` block, which F-Droid's scanner rejects).
