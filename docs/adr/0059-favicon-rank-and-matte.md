# ADR-0059: Rank favicon links, and matte a transparent dark mark onto white

Status: Accepted · Area: discovery

**Decision.** `StationUrlResolver.extractFavicon()` scores every `<link>` whose `rel` contains `icon`, plus each `imagesrcset` candidate, and returns the highest score. `rel="mask-icon"` and a `media` of `prefers-color-scheme: dark` are ranked last; SVG and `.ico` rank below PNG/JPEG/WebP/GIF; a declared size of 16px or less ranks below an unknown or larger size; among the rest, the larger `sizes` / `w` descriptor wins, capped at 256. Ties keep the earlier URL. `IconStorage.persistBitmap()` still writes a `.jpg`. Before that write, a bitmap with alpha is composited onto white when at least 5% of its pixels are transparent and the opaque pixels average darker than 128. Light-on-transparent and mostly opaque images are unchanged.

**Because.** (scar) Adding `https://roughradio.live/` stored the first non-`.ico` link, `cropped-logo-dashboard-copia-2-scaled-1-32x32.png`. The file is a black sunburst on a transparent PNG (the 192px and apple-touch icons on that page are the same mark). `Bitmap.compress(JPEG)` drops alpha and keeps the RGB, which is black for both the mark and the transparent pixels. The station list clips the result to a circle, so the player shows a plain black disc. The same flattening hits any dark monochrome favicon chosen ahead of a real raster (a mask icon, a dark-mode SVG, a 1×1 placeholder, the small `imagesrcset` candidate).

**Accepting.** A dark logo gains a white matte, which reads as a light badge on the always-dark UI and in Android Auto. A page whose only icon is an SVG or a mask icon still gets that URL and may fail to decode. `og:image` is not a candidate.

**Rejected.**

- Saving PNG with alpha — every stored icon is already a `UUID.jpg`, and a transparent black mark on the dark UI is still invisible.
- Always matting on white — light logos on transparent already survive as light-on-black and would disappear on white.
- Preferring the in-page wordmark / `og:image` on roughradio.live — that asset is a wide banner (`Rough_Radio-LOGO-CORTO-…png`), not the site icon, and `ContentScale.Crop` in the circular list slot would keep a fragment of the lettering. The declared icons are the sunburst; once the matte is white, that sunburst is the icon.
- Downloading every candidate and scoring pixels inside the resolver — these three URLs are the same black mark, so pixel scoring cannot pick a different picture. The matte is what makes the bytes visible, and extra fetches spend the resolve budget.

**Revisit.** If a station's real logo exists only as `og:image` or a web manifest, and the favicon links are monochrome placeholders that still look wrong after the white matte.
