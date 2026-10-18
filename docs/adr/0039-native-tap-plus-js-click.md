# ADR-0039: The sniffer taps play with a real `MotionEvent`, then always also a JS click

Status: Accepted · Area: discovery

**Decision.** `clickPlayControl()` locates a play-looking element (`LOCATE_PLAY_CONTROL_JS`: text/aria-label/class matching `play`/`▶`/`слуш`/`listen`/...) and dispatches a native touch at its coordinates (`View.dispatchTouchEvent`). `FALLBACK_CLICK_JS` (`element.click()`) then *always* runs too.

**Because.**
- (scar) Chromium's autoplay policy doesn't count a JS-synthesized click as user activation — confirmed on the Supabase case: the click ran, the gated `play()` never fired the stream request.
- Many players request the real stream only once playback starts.
- A never-attached WebView has no confirmed-reliable `dispatchTouchEvent` across OEM/WebView-provider combos, and a silent no-op doesn't throw.

**Rejected.**
- JS `element.click()` alone — not user activation.
- JS click only when the native tap throws — "no exception" isn't proof the tap reached Chromium.
