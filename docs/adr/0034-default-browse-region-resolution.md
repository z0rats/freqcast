# ADR-0034: Default-browse region follows the in-app language first, then the raw system region

Status: Accepted · Area: discovery

**Decision.** `resolveDefaultBrowseRegionCode()`:
1. An explicit in-app language (`AppCompatDelegate.getApplicationLocales()`) wins. Picker tags are language-only (`"ru"`, `"es"`) except `"zh-CN"`; `LANGUAGE_TO_DEFAULT_REGION` fills the region (`"en"` deliberately absent — ambiguous across US/GB/...).
2. Otherwise the device's *raw system* region: `Resources.getSystem().configuration.locales[0].country`.
3. `CountryCatalog.englishNameForRegion()` (full ISO 3166-1 lookup, separate from the 10-flag picker) → `SearchBy.COUNTRY`. Empty/unresolvable region or empty result → `RadioBrowserApi.topStations()` (`order=votes`).
4. The header re-derives a *localized* country name at render time (`Locale.getDisplayCountry(Locale.getDefault())`).

**Because.** Switching the UI to Russian should browse Russian stations even if the system region is `"US"` — otherwise results look random.

**Rejected.**
- `appContext`'s resources for the region — already reflect language-only overrides.
- Reusing the English query string in the header — a Russian UI would show "Russia" instead of "Россия".
