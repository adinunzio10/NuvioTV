# Source-failure diagnostic probe — design

**Date:** 2026-07-11 · **Area:** Player / error handling · **Related:** ISSUES.md #1, #4

## Problem

When playback fails with a "bad content" error, the player can only show a generic message
("the link may have expired or the server returned an error page — try a different source").
It cannot tell the user *why* the link failed, because by the time the error surfaces the
response body has been consumed by the extractor and the only retained data is thin
(URL/host, request headers, a *guessed* mimeType). Notably, `currentStreamResponseHeaders`
is the addon's *predicted* headers (`behaviorHints.proxyHeaders.response`), not what the
server actually sent — see `PlayerRuntimeControllerStreams.kt:506`.

Key insight: a pure 3003 (`UnrecognizedInputFormatException` / `PARSING_CONTAINER_UNSUPPORTED`)
implies the server returned **HTTP 200 with a non-video body** — an expired/blocked/removed
link normally returns 4xx, which already surfaces as `InvalidResponseCodeException` and is
already translated to a specific message. So the interesting "why" for a 3003 is knowable only
by looking at the body — which we no longer have.

## Goal

On a content-shaped playback failure, **actively re-fetch the first few KB of the source** and
classify the response to show the user a *specific* reason (error page, Cloudflare challenge,
expired, geo-blocked, unreachable, …), falling back to the current generic message when the
bytes are genuinely unrecognized video with no tells.

## Scope (this iteration)

**In:**
- First-bytes probe (range-GET of the start) + response classification.
- Triggers on "content-shaped" failures only: 3003 (`UnrecognizedInputFormatException`) and
  `ERROR_CODE_IO_UNSPECIFIED` whose root cause is a parse/`IllegalStateException` (the varint
  family). NOT network failures (timeouts, connection reset, unknown host) — those are already
  self-explanatory and re-fetching adds nothing.
- English strings only.

**Out (deferred):**
- Tail / `Content-Length` truncation detection (the varint/#4 family: first bytes look like
  valid video, so a first-bytes probe returns INCONCLUSIVE there — detecting truncation needs a
  tail check). Tracked in ISSUES.md #4.
- Auto-advancing to the next source on a confirmed-dead link (separate behavioral change).
- Translating the new strings (22 locales keep old/absent copy until refreshed).

## Components (isolated, testable)

### 1. `SourceFailureClassifier` — pure, no I/O

The brains. A pure function so it is fully unit-testable without a network or Android runtime.

```
data class SourceProbeInput(
    val probeFailed: Boolean,      // connection threw (couldn't reach source)
    val httpStatus: Int?,          // null if unknown
    val contentType: String?,      // raw Content-Type header
    val bodySnippet: String?       // first ~4 KB decoded as UTF-8 (may be binary garbage)
)

enum class SourceFailureReason {
    UNREACHABLE, EXPIRED, NOT_FOUND, BLOCKED, RATE_LIMITED,
    CHALLENGE, GEO_BLOCKED, ERROR_PAGE, INCONCLUSIVE
}

data class SourceFailureDiagnosis(
    val reason: SourceFailureReason,
    val httpStatus: Int? = null    // carried for ERROR_PAGE message ("HTTP 500")
)

fun classifySourceFailure(input: SourceProbeInput): SourceFailureDiagnosis
```

**Classification priority:**
1. `probeFailed` → `UNREACHABLE`.
2. HTTP status: `410→EXPIRED`, `404→NOT_FOUND`, `403→BLOCKED`, `429→RATE_LIMITED`,
   any other `4xx/5xx → ERROR_PAGE` (carry the code).
3. `2xx` / unknown status, inspect body + content-type (case-insensitive):
   - Cloudflare / captcha markers (`cf-ray`, `just a moment`, `cdn-cgi`, `attention required`,
     `captcha`) → `CHALLENGE`.
   - HTML (content-type `text/html` **or** body trimmed starts with `<!doctype`/`<html`):
     keyword scan → `expired`→EXPIRED · `not found`/`deleted`/`removed`→NOT_FOUND ·
     `forbidden`/`access denied`→BLOCKED · `rate`/`too many`/`quota`→RATE_LIMITED ·
     `region`/`country`/`geo`/`not available in your`→GEO_BLOCKED · else `ERROR_PAGE`.
   - JSON (content-type `application/json`) → `ERROR_PAGE`.
   - else (binary / unrecognized video bytes, no tells) → `INCONCLUSIVE`.

Keyword/marker tables are module constants.

### 2. `SourceFailureProbe` — network wrapper (suspend)

Reuses `PlayerPlaybackNetworking.openConnection(...)` (same helper the MIME probe uses) with a
range-GET `bytes=0-4095` and the existing `PROBE_TIMEOUT_MS` (4 s). Reads the status,
`Content-Type`, `Content-Length`, and up to ~4 KB of body → builds `SourceProbeInput` → returns
`classifySourceFailure(...)`. Any connection exception → `SourceProbeInput(probeFailed = true)`.
No caching (failure diagnosis must be live). Runs on `Dispatchers.IO`. The probe also emits the
diagnostics log (see below) — the classifier stays pure; all I/O and logging live here.

### 3. `onPlayerError` integration — async, non-blocking

In `PlayerRuntimeControllerInitialization.kt`, on the final-error path (after auto-retries are
exhausted) when the error is content-shaped:
1. Show the generic friendly message **immediately** (unchanged: `error.toDisplayMessage(context)`)
   so the user never sees a spinner.
2. Capture the current `currentStreamUrl` and `scope.launch` the probe.
3. When the probe returns, apply the refined message **only if** the guard still holds —
   `currentStreamUrl` is unchanged (no new stream/retry started) **and** `_uiState.value.error`
   is still non-null (the error screen is still up) — and `reason != INCONCLUSIVE`. On
   INCONCLUSIVE or timeout, leave the generic message. This reuses existing state; no new
   generation counter is introduced.

Raw `detailedError` continues to be recorded for diagnostics (unchanged).

### 4. Message mapping — `SourceFailureDiagnosis.toDisplayMessage(context, errorCodeName)`

Reuses existing strings where possible; adds a few. All keep the format "…concise reason…
Try a different source. [<errorCodeName>]" to fit the existing "Playback Error" view.

| Reason | String |
|--------|--------|
| EXPIRED | existing `player_error_stream_expired` |
| NOT_FOUND | existing `player_error_stream_removed` |
| BLOCKED | existing `player_error_stream_blocked` |
| RATE_LIMITED | existing `player_error_stream_rate_limited` |
| CHALLENGE | new — "behind a bot check (e.g. Cloudflare); it returned a challenge page, not the video." |
| GEO_BLOCKED | new — "appears to be blocked in your region." |
| ERROR_PAGE | new — "returned an error page instead of the video (HTTP %d)." |
| UNREACHABLE | new — "couldn't be reached to play or diagnose; it may be down." |
| INCONCLUSIVE | improved generic copy (the already-edited `player_error_source_invalid_content`). |

## Diagnostics logging

The classifier's reason set will inevitably miss error pages we haven't seen. Logging is how the
keyword/marker tables grow — especially for `INCONCLUSIVE` (unrecognized) and `ERROR_PAGE` /
`CHALLENGE` that matched no specific keyword. Emitted by the probe layer under the existing
`PlayerViewModel` tag with a `SOURCE_DIAG` prefix (so it lands in the same logcat capture already
used for debugging). Always on — these dumps only fire on rare content-shaped failures, and gating
them behind a flag would mean they're off exactly when an unseen error page appears in the wild.

**Always (any diagnosis) — one greppable summary line:**
```
SOURCE_DIAG reason=INCONCLUSIVE status=200 contentType=text/html len=1834 host=<host>
```

**Additionally, when the response status is 2xx or unknown** (i.e. the server said "OK" but the
body wasn't playable video — the case where keyword discovery matters; a 4xx/5xx already tells us
the cause from its code, so it gets only the summary line):
- **Text body** (content-type text/html/json, or bytes mostly printable): a **bounded, sanitized
  snippet** — first ~1–2 KB, printable chars only, whitespace-collapsed, hard length cap — so new
  keywords can be read straight from logcat.
- **A replayable request dump** in curl form (`method`, full URL, `-H` headers) so the full body can
  be pulled on a laptop when the snippet isn't enough.
- **Binary body** (unrecognized non-text bytes, e.g. truncated MKV): skip the text dump; instead log
  the **hex of the first ~16 bytes** + total length. Magic numbers identify the real container for
  free (`1A45DFA3` = Matroska/EBML, `…ftyp` = MP4) — directly useful for the #4 varint family.

The reason enum and message stay unchanged (`INCONCLUSIVE` still shows the improved generic copy);
this is logging only.

## Testing

- `SourceFailureClassifierTest` (pure JVM): table-driven (status × content-type × body) →
  expected `SourceFailureReason`, including edge cases (200 + HTML "not found", 200 + Cloudflare,
  200 + JSON, 200 + binary → INCONCLUSIVE, probeFailed → UNREACHABLE, 410/404/403/429, 503).
- Message-mapping test with a mocked `Context` (pattern from `PlaybackErrorDisplayMessageTest`).
- TDD: classifier first (red→green), then wire the probe, then integration.

## Privacy / safety

The probe re-requests the **same** stream URL with the **same** headers already in use — no new
endpoint, no user data goes anywhere it wasn't already going; nothing is sent to any third party.

The diagnostics logging (above) intentionally writes a failed source's error-page body snippet and
a token-bearing URL/headers to logcat. This is a deliberate tradeoff for a self-maintained fork:
it only fires on rare content-shaped failures, and it is **consistent with what the app already
logs today** — `Resolved stream mimeType=… for url=…` already prints the full tokenized stream URL.
The data stays on-device (logcat), tied to the user's own debrid account. If this is ever
distributed more widely, revisit gating the verbose snippet/curl dump behind a debug toggle.

## Files

- New: `core/player/SourceFailureClassifier.kt` (classifier + reason enum + diagnosis type + probe),
  `app/src/test/.../SourceFailureClassifierTest.kt`.
- Modify: `PlayerRuntimeControllerInitialization.kt` (`onPlayerError` async refine),
  `PlayerRuntimeControllerErrorRecovery.kt` (message mapping helper, alongside `toDisplayMessage`),
  `app/src/main/res/values/strings.xml` (new strings; keep the improved INCONCLUSIVE copy).

## Known limitations (accept + document)

- **Post-hoc:** the probe runs after the failure; server state may differ slightly.
- **Single-use debrid links:** a re-fetch may return 404/410 even if the original failure differed,
  so NOT_FOUND/EXPIRED wording is phrased as "no longer available" rather than an absolute cause.
- **Truncated files** (varint/#4) return INCONCLUSIVE here → generic message; real fix is the
  deferred tail check.
