# ADR-0040: The sniffer never captures `OPTIONS` requests

Status: Accepted · Area: discovery

**Decision.** `shouldCaptureRequest(url, method)` excludes `OPTIONS`.

**Because (scar).** Confirmed on a real device (surprise.fm): a CORS preflight hits the same URL first, with `Access-Control-Request-Headers` merely *naming* `apikey`. It won `runSniff`'s `distinctBy`-by-url dedup over the real credentialed request, and replaying its headers 401s against Supabase/PostgREST.
