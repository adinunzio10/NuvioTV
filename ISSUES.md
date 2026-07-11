# Issues

A lightweight, in-repo tracker for bugs and improvements in this fork. The GitHub
Issues tab is disabled on the fork, so this file is the running list.

**Status legend:** 🔴 open · 🟡 in progress · 🟢 done · ⚪ wontfix/deferred

| # | Status | Area | Title |
|---|--------|------|-------|
| 1 | 🔴 | Player | Improve handling of 3003 (`UnrecognizedInputFormatException`) errors |
| 2 | 🔴 | Auth | QR sign-in broken after mass logout (anonymous sign-in fails; tight retry loop) |
| 3 | 🔴 | Player | Auto-pick stream should skip Comet's debrid cache-refresh entry |
| 4 | 🔴 | Player | Surface more source diagnostics on 2000 (`IO_UNSPECIFIED`) MKV/EBML parse failures |

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

- [x] Route the `onPlayerError` final-error display through `toDisplayMessage()` so 3003 (and
  HTTP-status errors) get the user-friendly, actionable message. Keep the raw `detailedError`
  for logs/diagnostics only. **Done** — `PlayerRuntimeControllerInitialization.kt` `onPlayerError`
  now sets `error = error.toDisplayMessage(context)`; regression coverage in
  `PlaybackErrorDisplayMessageTest`.
- [ ] On a parsing error where MIME was `null`, attempt one re-probe (active range-GET +
  `#EXTM3U`/`<MPD` sniff) before giving up, so a misdetected HLS/DASH stream can be re-routed
  to the correct MediaSource on retry. **Deferred.** Note (diagnosis correction): a re-probe
  *already* happens on the second auto-retry — when `currentStreamMimeType` is `null`,
  `initializePlayer` → `resolveCurrentStreamMimeType` calls `probeMimeType` (range-GET + sniff),
  since null MIMEs are never cached (nothing for `handleParsingErrorFallback` to evict). The real
  gap is narrower: `attemptAutoRetry`'s *first* attempt does a lightweight `player.prepare()` that
  reuses the same progressive source (no re-route), burning one retry before the rebuild+re-probe
  on attempt 2. Closing it means changing the tested retry logic.
- [ ] (Optional) When the failure is genuinely non-video content (cause #1), make the "Try a
  different source" affordance more prominent / consider auto-advancing to the next source.
  **Deferred.**

### Validation (2026-07-11)

Confirmed on-device (Shield, first build with this fix). A real 3003 during normal playback now
shows the friendly, actionable string — reported verbatim as:

> Source error: The stream source returned invalid or unplayable content. The link may have
> expired or the server returned an error page instead of video. Try a different source.
> [ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED]

i.e. the change #1 message fix works as intended (old build would have shown the raw
"None of the available extractors … could read the stream. [3003]"). Note: on this occurrence a
source reload *succeeded* (previously reloads reliably failed) — but the message fix is display-only
and does **not** change retry/reload behavior, so that recovery is either a healthy link on retry
(cause #1) or, if it was the *same* source, a transient/misdetected stream (cause #2 → the deferred
re-probe item above). Not enough signal yet to distinguish.

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

---

## #2 — QR sign-in broken after mass logout (anonymous sign-in fails; tight retry loop)

**Status:** 🔴 open · **Area:** Auth / QR sign-in

### Summary

After a NuvioTV backend event logged everyone out, the QR sign-in screen no longer produces a
scannable code — it shows "generating…" / "QR unavailable" instead. Account isn't required to use
the app, but the sign-in flow is broken. Needs more diagnosis (see caveat below).

### What we know so far (2026-07-09, partial)

The QR flow depends on a **Supabase anonymous sign-in** to mint a device session before it can
generate the login code/QR. `QrCodeGenerator.generate()` is only reached once that succeeds; with
no code, `uiState.qrLoginBitmap` stays null → "unavailable". So a broken QR is a *symptom* of the
anonymous sign-in failing, not (necessarily) a QR-rendering bug.

Two observations from a Shield capture:

1. **Retry storm (genuine, environment-independent).** When anonymous sign-in fails, the app
   retries in a tight loop — ~82 `POST /auth/v1/signup` attempts in ~2 seconds (every 50–80 ms),
   no backoff. Logged as `E AuthManager: QR anonymous sign in failed` +
   `W AccountViewModel: Raw error: …`. This hammers the auth endpoint and would make any
   server-side rate-limiting/outage worse. Likely a `LaunchedEffect`/state re-trigger in
   `AuthQrSignInScreen` (`startQrLogin()`) and/or an inner retry with no ceiling.
2. **Localhost failure was OUR build artifact, not the real bug.** Our locally-built *debug* APK
   has no `local.dev.properties`, so `SUPABASE_URL` defaulted to `https://localhost` →
   `Connection refused`. This masks the real production cause. The production app (`com.nuvio.app`)
   created a real Supabase client and reached `Supabase-Auth: No session found. NotAuthenticated`
   but we did not yet capture it attempting/​failing the QR sign-in.

### TODO / next diagnosis

- [ ] Capture production (`com.nuvio.app`) logcat while actually on the QR sign-in screen to see
  the *real* anonymous-sign-in failure (status code / message from the live backend).
- [ ] Fix the retry storm regardless of root cause: add backoff + a max-attempt ceiling so a
  failing sign-in doesn't spam `signup` dozens of times/second.
- [ ] Surface a real error state on the QR screen instead of a perpetual "generating…".

### Affected files (starting points)

- `app/src/main/java/com/nuvio/tv/ui/screens/account/AuthQrSignInScreen.kt` (`startQrLogin`,
  `LaunchedEffect` gating)
- `AccountViewModel` (`startQrLogin` / `Raw error:` logging) and the `AuthManager` that performs
  the Supabase anonymous sign-in
- `app/src/main/java/com/nuvio/tv/core/qr/QrCodeGenerator.kt` (downstream; only reached on success)

---

## #3 — Auto-pick stream should skip Comet's debrid cache-refresh entry

**Status:** 🔴 open · **Area:** Player / auto-select

### Summary

The "auto pick stream" setting normally selects the first stream in the list. The **Comet** addon
places a special entry at the very top that is *not a playable title* — it exists to refresh your
debrid library cache. Auto-pick frequently lands on this entry, forcing the user to "play manually"
and choose a real stream instead. If we could detect that top cache-refresh/non-playable entry and
skip it during auto-select, that would avoid the dead pick.

### Notes (not yet investigated)

- Need to identify how Comet's cache-refresh entry is distinguishable from real streams (title /
  name pattern, missing size/quality, a marker field, or infoHash/URL shape).
- Fix likely lives in the auto-select logic that picks the first stream; skip entries matching the
  cache-refresh signature rather than removing them from the list.

**Was hypothesized to share a root cause with #4, but log evidence (2026-07-11) refuted that** —
#4's captured failure was a truncated MKV from AIOStreams/ElfHosted, with no Comet involvement. #3
stands on its own: still need to characterize how Comet's refresh entry is distinguishable so
`StreamAutoPlaySelector` can skip it.

_Reported 2026-07-09. Left for later investigation per request — placeholder only._

---

## #4 — Surface more source diagnostics on 2000 (`IO_UNSPECIFIED`) MKV/EBML parse failures

**Status:** 🔴 open · **Area:** Player / error handling · **Related:** #1

### Summary

Observed on-device (2026-07-09, arm64 Shield, first build with the #1 message fix):

> `Playback Error`
> `Unexpected IllegalStateException: No valid varint length mask found [ERROR_CODE_IO_UNSPECIFIED]`

This is a distinct failure from #1's 3003, **not** the 3003 in disguise. "No valid varint length
mask found" is an **EBML** parse error — EBML is the variable-length-integer container format
underlying **Matroska (MKV) / WebM**. It means the Matroska extractor *did* accept the stream as
MKV and began parsing, then hit a byte whose EBML length descriptor was invalid (corrupt,
truncated, or misaligned data mid-stream). It surfaces as `ERROR_CODE_IO_UNSPECIFIED` (2000)
because it bubbles up as an unexpected `IllegalStateException` through ExoPlayer's `Loader`, not
through the normal parser-error code path.

Contrast with #1's 3003 (`UnrecognizedInputFormatException` / `PARSING_CONTAINER_UNSUPPORTED`):
there **no** extractor recognized the bytes at all. Here the MKV extractor matched, then choked.
Both are plausibly the same *upstream* cause (a debrid/host link serving corrupt or partial
content), but mechanically different. `IO_UNSPECIFIED` is in `isRetryablePlaybackError`, so the
player already auto-retries twice before showing this — consistent with genuinely broken content.

### Not caused by the #1 change

The #1 fix only reformatted the *displayed message* (routing through `toDisplayMessage()`), which
is why the code now renders as the symbolic name `[ERROR_CODE_IO_UNSPECIFIED]` instead of the old
raw `[2000]`. The error itself would have occurred identically on the old build.

### Update (2026-07-11): recurring on next-episode auto-play — leading hypothesis links to #3

Now seen ~4× in 2 days, **usually on next-episode auto-play** (not menu playback). Leading
hypothesis: the next-episode selector silently picks **Comet's debrid cache-refresh entry** (see
#3) and hands its non-video URL to the Matroska extractor → EBML varint crash. Mechanism (code
read, not yet log-confirmed):

- `Stream.isExternal()` is `externalUrl != null && url == null && !magnet`. If Comet's refresh
  entry has a real `url`, it is **not** external, so `StreamAutoPlaySelector.isPlayable()` does
  **not** filter it (URL present, debrid state null/CACHED).
- Next-episode auto-play (`PlayerRuntimeControllerStreams.kt` ~1475–1600) prefers a stream whose
  `behaviorHints.bingeGroup` matches the current episode's (`preferBingeGroupInSelection`), via
  `selectAutoPlayStream` returning the first binge-group match. This path is **silent** — no picker,
  no "picked source" UI — unlike menu selection where the user sees Comet's "refreshing" message.
- So if Comet tags the refresh entry with the show's `bingeGroup`, auto-advance can grab it
  invisibly. This would make **#3 and #4 the same root cause**, surfacing two ways (menu = visible
  refresh message; next-episode = silent varint crash).

**To confirm:** capture logcat during a next-episode failure and read `Resolved stream mimeType=…
for url=…` — if the URL/host is Comet's refresh endpoint, confirmed. Fix then converges with #3
(detect + skip the refresh entry in selection). If the URL is a *normal* video source, this is
instead genuine mid-stream MKV corruption and #4 stays independent of #3.

### Log evidence (2026-07-11) — Comet hypothesis REFUTED; it's a truncated MKV

Captured a real occurrence on `com.nuvio.app`. The failing stream:

- Host **`aiostreams.elfhosted.com`** (AIOStreams / ElfHosted debrid) — **not Comet** (no "comet"
  anywhere in the log), and a **real episode** (Justice League Unlimited S02E03 "The Doomsday
  Sanction"), not a cache-refresh entry. Resolved as `video/x-matroska`.
- **Not a next-episode selection issue either.** Both the resolve line and the MediaSession
  metadata are the *same episode / same URL*; the episode had been playing continuously to
  `pos≈1371s` (~23 min, near the end). The error hit at the **tail of the episode being watched**,
  which is why it felt like "next episode starting."
- **Mechanism = truncated/stalled download.** For ~2.5 min before the error the buffer drained
  monotonically (`ahead=166s → … → 6s`) with `loading=false` — the download had stopped delivering.
  When playback consumed the buffer and the Matroska extractor tried to parse past the
  truncation/bad tail, EBML parsing threw `IllegalStateException: No valid varint length mask
  found` → wrapped by `Loader` as `IO_UNSPECIFIED` (2000). Auto-retried 2× (same URL, same bad
  region) and failed — consistent with genuinely bad content, not a transient glitch.

**Conclusion:** #4 is a bad/partial source from the debrid provider (same family as #1's 3003),
**independent of #3 (Comet)**. The Comet/binge-group hypothesis above is retained only as a record
of the investigation; the evidence refutes it for this occurrence.

### Proposed change (message-first)

The current message tells the user *what* the parser tripped on but nothing about *the source*,
which is what actually matters here (bad link vs. genuinely corrupt file). At minimum, enrich the
error surfaced for parse/`IO_UNSPECIFIED` failures with source context that's already known at
`onPlayerError` time, for logs and ideally a "details" affordance:

- [ ] Resolved `currentStreamMimeType` (or `unknown`) and how it was resolved (extension / header /
  probe) — a `mimeType=unknown` that still parsed as MKV is a strong "provider returned the wrong
  thing" signal.
- [ ] Stream host (`currentStreamUrl.safeHost()`), addon/provider name, and filename if present.
- [ ] Any captured response `Content-Type` (`currentStreamResponseHeaders`) — e.g. `text/html`
  behind an MKV guess = an error page.
- [ ] Keep the friendly "Try a different source" framing for the user; put the raw diagnostic
  detail in the diagnostics blob / logs (as #1 does for `detailedError`).

Optional follow-on (behavioral, larger): treat a mid-stream EBML parse failure like the other
"bad source" paths — surface/advance to another source rather than a dead error screen.

### Affected files

- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerErrorRecovery.kt`
  (`toDisplayMessage`, `findMostRelevantCauseMessage`)
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
  (`onPlayerError` — has `currentStreamMimeType`, `currentStreamResponseHeaders`, `currentStreamUrl`
  in scope)

### Repro / diagnosis notes

Intermittent, provider-side. When it recurs, `adb logcat` shows the
`Resolved stream mimeType=… for url=…` line just before the error — capture it to confirm whether
the source advertised MKV or was probed as `unknown`.
