# Issues

A lightweight, in-repo tracker for bugs and improvements in this fork. The GitHub
Issues tab is disabled on the fork, so this file is the running list.

**Status legend:** 🔴 open · 🟡 in progress · 🟢 done · ⚪ wontfix/deferred

| # | Status | Area | Title |
|---|--------|------|-------|
| 1 | 🔴 | Player | Improve handling of 3003 (`UnrecognizedInputFormatException`) errors |

---

## #1 — Improve handling of 3003 (`UnrecognizedInputFormatException`) errors

**Status:** 🔴 open · **Area:** Player / error handling

### Summary

When a stream fails with ExoPlayer error `[3003]` (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` /
`UnrecognizedInputFormatException`), the player shows a cryptic raw error message and the
retry logic generally cannot recover. Happens intermittently during normal use — sometimes
switching sources fixes it, sometimes no source works.

### What the error means

The `[3003]` code means ExoPlayer downloaded the first bytes of the stream and **none of the
progressive container extractors** (`Mp4Extractor`, `TsExtractor`, `MatroskaExtractor`, etc.
from `DefaultExtractorsFactory`) recognized it as video. Crucially `contentIsMalformed=false`
— this is *not* a corrupted video file, it's "these bytes aren't a video container at all."

Two real-world causes, both ending on the progressive path in
`PlayerMediaSourceFactory.createMediaSource` (the `else` branch):

1. **The link didn't return video** (most common, provider-side): the host/debrid/provider
   returned an HTML error page, JSON error, Cloudflare/captcha challenge, or an
   expired/not-found/geoblocked page instead of the file. Explains "different source works"
   (first link was dead) and "no source works" (a shared cause — unhealthy debrid account,
   IP rate-limited/blocked by hosts, DNS/network issue, or all candidate links stale at once).
2. **An adaptive stream misrouted to the progressive parser**: the stream is actually HLS/DASH
   but `probeMimeType` couldn't identify it (no extension in URL, generic/wrong `Content-Type`,
   or the probe HEAD/range-GET was slow/blocked and hit the 4s `PROBE_TIMEOUT_MS`). Feeding an
   `.m3u8` playlist to the video extractors produces exactly this 3003.

### Problems to fix

**1. Cryptic error message shown instead of the existing friendly one.**
The friendly string already exists and is exactly right:

> `player_error_source_invalid_content`: "Source error: The stream source returned invalid or
> unplayable content. The link may have expired or the server returned an error page instead
> of video.\n\nTry a different source. [%1$s]"

`PlayerRuntimeControllerErrorRecovery.kt#toDisplayMessage()` already maps
`UnrecognizedInputFormatException` to this string. **But** the `onPlayerError` handler in
`PlayerRuntimeControllerInitialization.kt` (~line 1332) sets the UI error to the raw
`detailedError` string instead of calling `toDisplayMessage()`:

```kotlin
_uiState.update { it.copy(error = detailedError, showLoadingOverlay = false, showPauseOverlay = false) }
```

This is why the screen shows
`Source error: None of the available extractors (...) could read the stream. (contentIsMalformed=false, dataType=1) [3003]`
instead of the friendly "try a different source" text.

**2. Retry/fallback can't re-detect MIME on the bare progressive path.**
`attemptAutoRetry` retries parsing errors twice and calls `handleParsingErrorFallback`, but
that fallback only evicts the MIME cache and re-probes **when `currentStreamMimeType != null`**
(`PlayerRuntimeControllerErrorRecovery.kt:433`). On the bare progressive path the MIME is
usually `null`, so the retry just re-fetches the same bytes → same 3003.

- For cause #1 this is fine (nothing to recover — the link is genuinely bad).
- For cause #2 it means a missed-detection HLS/DASH stream never gets a second chance to be
  re-sniffed and routed to `HlsMediaSource`/`DashMediaSource`.

### Proposed changes

- [ ] Route the `onPlayerError` final-error display through `toDisplayMessage()` so 3003 (and
  HTTP-status errors) get the user-friendly, actionable message. Keep the raw `detailedError`
  for logs/diagnostics only.
- [ ] On a parsing error where MIME was `null`, attempt one re-probe (active range-GET +
  `#EXTM3U`/`<MPD` sniff) before giving up, so a misdetected HLS/DASH stream can be re-routed
  to the correct MediaSource on retry.
- [ ] (Optional) When the failure is genuinely non-video content (cause #1), make the "Try a
  different source" affordance more prominent / consider auto-advancing to the next source.

### Repro / diagnosis notes

Intermittent — occurs on normal playback. To confirm the cause on a given failure, `adb logcat`
shows `Resolved stream mimeType=… for url=…` just before the error:
- `mimeType=unknown` on something that's actually HLS → cause #2 (misdetection).
- A normal MIME resolved but still 3003 → cause #1 (host returned an error page).

### Affected files

- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  (~line 1192–1332, `onPlayerError`)
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerErrorRecovery.kt`
  (`toDisplayMessage`, `handleParsingErrorFallback`)
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
  (`probeMimeType`, `inferMimeType`)
