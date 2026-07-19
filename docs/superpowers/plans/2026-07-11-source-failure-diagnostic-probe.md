# Source-Failure Diagnostic Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a content-shaped playback failure, actively re-fetch the start of the source, classify *why* it isn't playable, show the user a specific reason (with a generic fallback), and log enough to grow the classifier over time.

**Architecture:** A pure `classifySourceFailure()` maps `(probeFailed, httpStatus, contentType, bodySnippet)` → a reason. A suspend `probeSourceFailure()` does the network range-GET, feeds the classifier, and emits `SOURCE_DIAG` logging. `onPlayerError` shows the generic message immediately, then launches the probe and refines the message in place (guarded). Classifier + helpers are pure and unit-tested; probe + integration are thin wiring over tested units.

**Tech Stack:** Kotlin, AndroidX Media3 (`PlaybackException`, `UnrecognizedInputFormatException`), JUnit4 + MockK, existing `PlayerPlaybackNetworking.openConnection`.

## Global Constraints

- Module: `:app`. `minSdk = 24`, `compileSdk = 36`. Do NOT use `InputStream.readNBytes` (API 33) — use a manual read loop.
- Build/test env: export `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` and `ANDROID_HOME=$HOME/Library/Android/sdk` inline before any `./gradlew` (default JDK is 11 and fails).
- Test task: `./gradlew :app:testFullDebugUnitTest`. Shell is fish — quote any glob-like args.
- JVM unit tests: constructing a real `PlaybackException` calls `SystemClock.elapsedRealtime()` (unmocked → throws). Stub with `mockkStatic(SystemClock::class)` in `@Before` (pattern in `PlaybackErrorDisplayMessageTest`).
- New user-facing strings: English/default `values/strings.xml` only. Escape apostrophes as `\'`. Preserve `%1$s` / `%1$d` placeholders.
- All new player code lives in package `com.nuvio.tv.ui.screens.player`.
- Branch: `feat/source-failure-diagnostic-probe` (already checked out).
- The working tree already contains the improved `player_error_source_invalid_content` copy (the INCONCLUSIVE fallback) — leave it; it is committed as part of Task 6.

---

### Task 1: Pure classifier (`classifySourceFailure`) + types

**Files:**
- Create: `app/src/main/java/com/nuvio/tv/ui/screens/player/SourceFailureDiagnostics.kt`
- Test: `app/src/test/java/com/nuvio/tv/ui/screens/player/SourceFailureClassifierTest.kt`

**Interfaces:**
- Produces: `enum class SourceFailureReason { UNREACHABLE, EXPIRED, NOT_FOUND, BLOCKED, RATE_LIMITED, CHALLENGE, GEO_BLOCKED, ERROR_PAGE, INCONCLUSIVE }`; `data class SourceProbeInput(probeFailed: Boolean, httpStatus: Int?, contentType: String?, bodySnippet: String?)`; `data class SourceFailureDiagnosis(reason: SourceFailureReason, httpStatus: Int? = null)`; `fun classifySourceFailure(input: SourceProbeInput): SourceFailureDiagnosis`.

- [ ] **Step 1: Write the failing test**

Create `SourceFailureClassifierTest.kt`:

```kotlin
package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFailureClassifierTest {
    private fun classify(
        probeFailed: Boolean = false,
        status: Int? = null,
        contentType: String? = null,
        body: String? = null
    ) = classifySourceFailure(SourceProbeInput(probeFailed, status, contentType, body)).reason

    @Test fun `connection failure is unreachable`() =
        assertEquals(SourceFailureReason.UNREACHABLE, classify(probeFailed = true))

    @Test fun `http 410 is expired`() = assertEquals(SourceFailureReason.EXPIRED, classify(status = 410))
    @Test fun `http 404 is not found`() = assertEquals(SourceFailureReason.NOT_FOUND, classify(status = 404))
    @Test fun `http 403 is blocked`() = assertEquals(SourceFailureReason.BLOCKED, classify(status = 403))
    @Test fun `http 429 is rate limited`() = assertEquals(SourceFailureReason.RATE_LIMITED, classify(status = 429))
    @Test fun `http 500 is a generic error page`() = assertEquals(SourceFailureReason.ERROR_PAGE, classify(status = 500))

    @Test fun `200 with cloudflare challenge body`() =
        assertEquals(SourceFailureReason.CHALLENGE, classify(status = 200, contentType = "text/html", body = "<html><title>Just a moment...</title><div class=cf-ray>"))

    @Test fun `200 html saying not found`() =
        assertEquals(SourceFailureReason.NOT_FOUND, classify(status = 200, contentType = "text/html", body = "<html><body>File not found</body></html>"))

    @Test fun `200 html saying expired`() =
        assertEquals(SourceFailureReason.EXPIRED, classify(status = 200, contentType = "text/html", body = "<html>Your link has expired</html>"))

    @Test fun `200 html saying region blocked is geo`() =
        assertEquals(SourceFailureReason.GEO_BLOCKED, classify(status = 200, contentType = "text/html", body = "not available in your country"))

    @Test fun `200 generic html error page`() =
        assertEquals(SourceFailureReason.ERROR_PAGE, classify(status = 200, contentType = "text/html", body = "<html><body>Something went wrong</body></html>"))

    @Test fun `200 json is an error page`() =
        assertEquals(SourceFailureReason.ERROR_PAGE, classify(status = 200, contentType = "application/json", body = "{\"error\":\"bad\"}"))

    @Test fun `200 binary video bytes are inconclusive`() =
        assertEquals(SourceFailureReason.INCONCLUSIVE, classify(status = 200, contentType = "video/x-matroska", body = null))

    @Test fun `null status with no body is inconclusive`() =
        assertEquals(SourceFailureReason.INCONCLUSIVE, classify())

    @Test fun `error page carries the http status`() =
        assertEquals(503, classifySourceFailure(SourceProbeInput(false, 503, null, null)).httpStatus)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.SourceFailureClassifierTest"`
Expected: FAIL — `classifySourceFailure` / `SourceProbeInput` unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

Create `SourceFailureDiagnostics.kt`:

```kotlin
package com.nuvio.tv.ui.screens.player

import java.util.Locale

enum class SourceFailureReason {
    UNREACHABLE, EXPIRED, NOT_FOUND, BLOCKED, RATE_LIMITED,
    CHALLENGE, GEO_BLOCKED, ERROR_PAGE, INCONCLUSIVE
}

data class SourceProbeInput(
    val probeFailed: Boolean,
    val httpStatus: Int?,
    val contentType: String?,
    val bodySnippet: String?
)

data class SourceFailureDiagnosis(
    val reason: SourceFailureReason,
    val httpStatus: Int? = null
)

private val CHALLENGE_MARKERS = listOf(
    "cf-ray", "just a moment", "cdn-cgi", "attention required", "captcha",
    "cf-chl", "checking your browser", "cf-mitigated"
)
private val GEO_KEYWORDS = listOf(
    "not available in your", "your region", "your country", "geo-block",
    "geoblock", "geographically", "region locked"
)
private val EXPIRED_KEYWORDS = listOf("expired", "link has expired", "no longer available", "session has ended")
private val NOT_FOUND_KEYWORDS = listOf("not found", "deleted", "has been removed", "does not exist", "no such file")
private val BLOCKED_KEYWORDS = listOf("forbidden", "access denied", "unauthorized", "not authorized", "permission denied")
private val RATE_LIMIT_KEYWORDS = listOf("rate limit", "too many requests", "quota exceeded", "slow down")

fun classifySourceFailure(input: SourceProbeInput): SourceFailureDiagnosis {
    if (input.probeFailed) return SourceFailureDiagnosis(SourceFailureReason.UNREACHABLE)

    when (input.httpStatus) {
        410 -> return SourceFailureDiagnosis(SourceFailureReason.EXPIRED, 410)
        404 -> return SourceFailureDiagnosis(SourceFailureReason.NOT_FOUND, 404)
        403 -> return SourceFailureDiagnosis(SourceFailureReason.BLOCKED, 403)
        429 -> return SourceFailureDiagnosis(SourceFailureReason.RATE_LIMITED, 429)
    }
    val status = input.httpStatus
    if (status != null && status >= 400) {
        return SourceFailureDiagnosis(SourceFailureReason.ERROR_PAGE, status)
    }

    val contentType = input.contentType?.lowercase(Locale.US).orEmpty()
    val body = input.bodySnippet?.lowercase(Locale.US).orEmpty()
    val haystack = "$contentType\n$body"

    if (CHALLENGE_MARKERS.any { haystack.contains(it) }) {
        return SourceFailureDiagnosis(SourceFailureReason.CHALLENGE, status)
    }

    val trimmed = body.trimStart()
    val isHtml = contentType.contains("text/html") ||
        trimmed.startsWith("<!doctype") || trimmed.startsWith("<html")
    val isJson = contentType.contains("application/json")

    if (isHtml || isJson) {
        return SourceFailureDiagnosis(
            reason = when {
                GEO_KEYWORDS.any { body.contains(it) } -> SourceFailureReason.GEO_BLOCKED
                EXPIRED_KEYWORDS.any { body.contains(it) } -> SourceFailureReason.EXPIRED
                NOT_FOUND_KEYWORDS.any { body.contains(it) } -> SourceFailureReason.NOT_FOUND
                BLOCKED_KEYWORDS.any { body.contains(it) } -> SourceFailureReason.BLOCKED
                RATE_LIMIT_KEYWORDS.any { body.contains(it) } -> SourceFailureReason.RATE_LIMITED
                else -> SourceFailureReason.ERROR_PAGE
            },
            httpStatus = status
        )
    }

    return SourceFailureDiagnosis(SourceFailureReason.INCONCLUSIVE, status)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.SourceFailureClassifierTest"`
Expected: PASS (15 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/player/SourceFailureDiagnostics.kt \
        app/src/test/java/com/nuvio/tv/ui/screens/player/SourceFailureClassifierTest.kt
git commit -m "Add pure source-failure classifier" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Content-shaped failure predicate (`isContentShapedFailure`)

**Files:**
- Modify: `app/src/main/java/com/nuvio/tv/ui/screens/player/SourceFailureDiagnostics.kt` (append)
- Test: `app/src/test/java/com/nuvio/tv/ui/screens/player/SourceFailureTriggerTest.kt`

**Interfaces:**
- Consumes: `androidx.media3.common.PlaybackException`.
- Produces: `fun isContentShapedFailure(error: PlaybackException): Boolean`.

- [ ] **Step 1: Write the failing test**

Create `SourceFailureTriggerTest.kt`:

```kotlin
package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SourceFailureTriggerTest {
    @Before fun stub() { mockkStatic(SystemClock::class); every { SystemClock.elapsedRealtime() } returns 0L }
    @After fun unstub() { unmockkStatic(SystemClock::class) }

    private fun ex(cause: Throwable, code: Int) = PlaybackException("e", cause, code)

    @Test fun `3003 unrecognized format is content-shaped`() {
        val e = ex(UnrecognizedInputFormatException("x", io.mockk.mockk(relaxed = true)),
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
        assertTrue(isContentShapedFailure(e))
    }

    @Test fun `io_unspecified from IllegalState (varint) is content-shaped`() {
        val e = ex(IllegalStateException("No valid varint length mask found"),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertTrue(isContentShapedFailure(e))
    }

    @Test fun `io_unspecified from a socket timeout is NOT content-shaped`() {
        val e = ex(java.net.SocketTimeoutException("timeout"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertFalse(isContentShapedFailure(e))
    }

    @Test fun `network connection failure is NOT content-shaped`() {
        val e = ex(java.net.UnknownHostException("no host"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertFalse(isContentShapedFailure(e))
    }

    @Test fun `decoder error is NOT content-shaped`() {
        val e = ex(IllegalStateException("decoder"), PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertFalse(isContentShapedFailure(e))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.SourceFailureTriggerTest"`
Expected: FAIL — `isContentShapedFailure` unresolved.

- [ ] **Step 3: Write minimal implementation**

First add this import to the top of `SourceFailureDiagnostics.kt` (with the existing `import java.util.Locale`):

```kotlin
import androidx.media3.common.PlaybackException
```

Then append the functions to the body of `SourceFailureDiagnostics.kt`:

```kotlin
private inline fun <reified T : Throwable> Throwable.hasCauseOfType(): Boolean {
    var c: Throwable? = this
    while (c != null) { if (c is T) return true; c = c.cause }
    return false
}

fun isContentShapedFailure(error: PlaybackException): Boolean {
    if (error.hasCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>()) return true
    if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
        val networkCause = error.hasCauseOfType<java.net.SocketException>() ||
            error.hasCauseOfType<java.net.SocketTimeoutException>() ||
            error.hasCauseOfType<java.net.UnknownHostException>() ||
            error.hasCauseOfType<javax.net.ssl.SSLException>()
        if (networkCause) return false
        return error.hasCauseOfType<IllegalStateException>() ||
            error.hasCauseOfType<androidx.media3.common.ParserException>()
    }
    return false
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.SourceFailureTriggerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/player/SourceFailureDiagnostics.kt \
        app/src/test/java/com/nuvio/tv/ui/screens/player/SourceFailureTriggerTest.kt
git commit -m "Add content-shaped failure predicate for probe trigger" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Pure log helpers (snippet, hex, curl, textual detection)

**Files:**
- Modify: `app/src/main/java/com/nuvio/tv/ui/screens/player/SourceFailureDiagnostics.kt` (append)
- Test: `app/src/test/java/com/nuvio/tv/ui/screens/player/SourceDiagLogHelpersTest.kt`

**Interfaces:**
- Produces: `fun looksTextual(contentType: String?, bytes: ByteArray): Boolean`; `fun sanitizeBodySnippet(body: String, maxChars: Int = 2000): String`; `fun hexPreview(bytes: ByteArray, count: Int = 16): String`; `fun buildCurlCommand(url: String, headers: Map<String, String>): String`.

- [ ] **Step 1: Write the failing test**

Create `SourceDiagLogHelpersTest.kt`:

```kotlin
package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDiagLogHelpersTest {
    @Test fun `html content type is textual`() =
        assertTrue(looksTextual("text/html; charset=utf-8", "<html>".toByteArray()))

    @Test fun `video content type is not textual`() =
        assertFalse(looksTextual("video/mp4", byteArrayOf(0, 1, 2, 3)))

    @Test fun `mostly-binary bytes with no content type are not textual`() =
        assertFalse(looksTextual(null, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0)))

    @Test fun `printable bytes with no content type are textual`() =
        assertTrue(looksTextual(null, "Just a moment...".toByteArray()))

    @Test fun `snippet collapses whitespace and caps length`() {
        val out = sanitizeBodySnippet("a\n\n  b\t c", maxChars = 100)
        assertEquals("a b c", out)
    }

    @Test fun `snippet over the cap is truncated with a marker`() {
        val out = sanitizeBodySnippet("x".repeat(50), maxChars = 10)
        assertTrue(out.startsWith("xxxxxxxxxx"))
        assertTrue(out.contains("+40 chars"))
    }

    @Test fun `hex preview shows uppercase magic bytes`() =
        assertEquals("1A 45 DF A3", hexPreview(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), count = 4))

    @Test fun `curl command quotes url and headers`() {
        val out = buildCurlCommand("https://h/x?t=1", mapOf("Referer" to "https://h/"))
        assertTrue(out.contains("-H 'Referer: https://h/'"))
        assertTrue(out.contains("'https://h/x?t=1'"))
        assertTrue(out.contains("-r 0-"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.SourceDiagLogHelpersTest"`
Expected: FAIL — helpers unresolved.

- [ ] **Step 3: Write minimal implementation**

Append to `SourceFailureDiagnostics.kt`:

```kotlin
fun looksTextual(contentType: String?, bytes: ByteArray): Boolean {
    contentType?.lowercase(Locale.US)?.let { ct ->
        if (ct.contains("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("html")) return true
        if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("octet-stream")) return false
    }
    if (bytes.isEmpty()) return false
    val sample = bytes.take(256)
    val printable = sample.count {
        val v = it.toInt() and 0xFF
        v == 0x09 || v == 0x0A || v == 0x0D || v in 0x20..0x7E
    }
    return printable.toDouble() / sample.size >= 0.85
}

fun sanitizeBodySnippet(body: String, maxChars: Int = 2000): String {
    val collapsed = body.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length > maxChars) {
        collapsed.take(maxChars) + "…[+${collapsed.length - maxChars} chars]"
    } else collapsed
}

fun hexPreview(bytes: ByteArray, count: Int = 16): String =
    bytes.take(count).joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

fun buildCurlCommand(url: String, headers: Map<String, String>): String {
    val headerArgs = headers.entries.joinToString(" ") { (k, v) ->
        "-H '$k: ${v.replace("'", "'\\''")}'"
    }
    val prefix = "curl -sS -D - -r 0-4095"
    return if (headerArgs.isBlank()) "$prefix '$url'" else "$prefix $headerArgs '$url'"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.SourceDiagLogHelpersTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/player/SourceFailureDiagnostics.kt \
        app/src/test/java/com/nuvio/tv/ui/screens/player/SourceDiagLogHelpersTest.kt
git commit -m "Add pure log helpers for source diagnostics" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Message mapping + new strings

**Files:**
- Modify: `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerErrorRecovery.kt` (append extension)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/nuvio/tv/ui/screens/player/SourceFailureMessageTest.kt`

**Interfaces:**
- Consumes: `SourceFailureDiagnosis`, `SourceFailureReason` (Task 1).
- Produces: `internal fun SourceFailureDiagnosis.toDisplayMessage(context: Context, errorCodeName: String): String`.

- [ ] **Step 1: Add the new string resources**

In `app/src/main/res/values/strings.xml`, immediately after the `player_error_source_invalid_content` line, add:

```xml
    <string name="player_error_source_challenge">This source is behind a bot check (e.g. Cloudflare) and returned a challenge page instead of the video. Try a different source.</string>
    <string name="player_error_source_geo_blocked">This source appears to be blocked in your region. Try a different source.</string>
    <string name="player_error_source_error_page">The server returned an error page instead of the video (HTTP %1$d). Try a different source.</string>
    <string name="player_error_source_unreachable">This source couldn\'t be reached to play or diagnose — it may be down. Try a different source.</string>
```

- [ ] **Step 2: Write the failing test**

Create `SourceFailureMessageTest.kt`:

```kotlin
package com.nuvio.tv.ui.screens.player

import android.content.Context
import com.nuvio.tv.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFailureMessageTest {
    private fun context() = mockk<Context>().also { c ->
        every { c.getString(R.string.player_error_stream_expired) } returns "\n\nEXPIRED"
        every { c.getString(R.string.player_error_source_challenge) } returns "CHALLENGE"
        every { c.getString(R.string.player_error_source_error_page, any()) } returns "ERRORPAGE-503"
        every { c.getString(R.string.player_error_source_invalid_content, any()) } returns "GENERIC"
    }

    @Test fun `expired reuses the stream-expired string trimmed and appends the code`() {
        val msg = SourceFailureDiagnosis(SourceFailureReason.EXPIRED, 410).toDisplayMessage(context(), "CODE")
        assertEquals("EXPIRED [CODE]", msg)
    }

    @Test fun `challenge maps to the challenge string with the code`() {
        val msg = SourceFailureDiagnosis(SourceFailureReason.CHALLENGE).toDisplayMessage(context(), "CODE")
        assertEquals("CHALLENGE [CODE]", msg)
    }

    @Test fun `error page maps with the http status`() {
        val msg = SourceFailureDiagnosis(SourceFailureReason.ERROR_PAGE, 503).toDisplayMessage(context(), "CODE")
        assertEquals("ERRORPAGE-503 [CODE]", msg)
    }

    @Test fun `inconclusive uses the generic string which already embeds the code`() {
        val msg = SourceFailureDiagnosis(SourceFailureReason.INCONCLUSIVE).toDisplayMessage(context(), "CODE")
        assertEquals("GENERIC", msg)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.SourceFailureMessageTest"`
Expected: FAIL — `toDisplayMessage(Context, String)` on `SourceFailureDiagnosis` unresolved.

- [ ] **Step 4: Write minimal implementation**

Append to `PlayerRuntimeControllerErrorRecovery.kt` (it already imports `com.nuvio.tv.R`):

```kotlin
internal fun SourceFailureDiagnosis.toDisplayMessage(
    context: android.content.Context,
    errorCodeName: String
): String {
    if (reason == SourceFailureReason.INCONCLUSIVE) {
        return context.getString(R.string.player_error_source_invalid_content, errorCodeName)
    }
    val line = when (reason) {
        SourceFailureReason.EXPIRED -> context.getString(R.string.player_error_stream_expired)
        SourceFailureReason.NOT_FOUND -> context.getString(R.string.player_error_stream_removed)
        SourceFailureReason.BLOCKED -> context.getString(R.string.player_error_stream_blocked)
        SourceFailureReason.RATE_LIMITED -> context.getString(R.string.player_error_stream_rate_limited)
        SourceFailureReason.CHALLENGE -> context.getString(R.string.player_error_source_challenge)
        SourceFailureReason.GEO_BLOCKED -> context.getString(R.string.player_error_source_geo_blocked)
        SourceFailureReason.ERROR_PAGE -> context.getString(R.string.player_error_source_error_page, httpStatus ?: 0)
        SourceFailureReason.UNREACHABLE -> context.getString(R.string.player_error_source_unreachable)
        SourceFailureReason.INCONCLUSIVE -> "" // handled above
    }.trim()
    return "$line [$errorCodeName]"
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.SourceFailureMessageTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerErrorRecovery.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/com/nuvio/tv/ui/screens/player/SourceFailureMessageTest.kt
git commit -m "Map source-failure reasons to user messages" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Network probe + `SOURCE_DIAG` logging

**Files:**
- Modify: `app/src/main/java/com/nuvio/tv/ui/screens/player/SourceFailureDiagnostics.kt` (append)

**Interfaces:**
- Consumes: `PlayerPlaybackNetworking.openConnection(url, headers, method, connectTimeoutMs, readTimeoutMs, range)`; `PlayerMediaSourceFactory.sanitizeHeaders(headers)`; `PlayerRuntimeController.TAG`; helpers/classifier from Tasks 1 & 3.
- Produces: `suspend fun probeSourceFailure(url: String, headers: Map<String, String>): SourceFailureDiagnosis`.

**Note:** No unit test — this is thin I/O wiring over already-tested pure units (`classifySourceFailure`, `looksTextual`, the log helpers). Verified on-device in Task 7. Keep the wiring minimal so all logic stays in the tested functions.

- [ ] **Step 1: Write the implementation**

First add these imports to the top of `SourceFailureDiagnostics.kt`:

```kotlin
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
```

Then append to the body of `SourceFailureDiagnostics.kt`:

```kotlin
private const val SOURCE_DIAG_TIMEOUT_MS = 4000
private const val SOURCE_DIAG_MAX_BYTES = 4096

suspend fun probeSourceFailure(url: String, headers: Map<String, String>): SourceFailureDiagnosis =
    withContext(Dispatchers.IO) {
        val sanitized = PlayerMediaSourceFactory.sanitizeHeaders(headers)
        val requestHeaders = sanitized.toMutableMap().apply { put("Connection", "close") }

        var status: Int? = null
        var contentType: String? = null
        var contentLength: String? = null
        var bytes = ByteArray(0)
        var probeFailed = false
        var connection: HttpURLConnection? = null
        try {
            connection = PlayerPlaybackNetworking.openConnection(
                url = url,
                headers = requestHeaders,
                method = "GET",
                connectTimeoutMs = SOURCE_DIAG_TIMEOUT_MS,
                readTimeoutMs = SOURCE_DIAG_TIMEOUT_MS,
                range = "bytes=0-${SOURCE_DIAG_MAX_BYTES - 1}"
            )
            status = connection.responseCode
            contentType = connection.contentType
            contentLength = connection.getHeaderField("Content-Length")
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            bytes = stream?.readUpTo(SOURCE_DIAG_MAX_BYTES) ?: ByteArray(0)
        } catch (_: Exception) {
            probeFailed = true
        } finally {
            runCatching { connection?.disconnect() }
        }

        val textual = !probeFailed && looksTextual(contentType, bytes)
        val bodyText = if (textual) String(bytes, Charsets.UTF_8) else null
        val diagnosis = classifySourceFailure(
            SourceProbeInput(probeFailed, status, contentType, bodyText)
        )
        logSourceDiag(url, sanitized, diagnosis, status, contentType, contentLength, bytes, textual, bodyText)
        diagnosis
    }

private fun InputStream.readUpTo(max: Int): ByteArray {
    val buffer = ByteArray(max)
    var total = 0
    while (total < max) {
        val read = read(buffer, total, max - total)
        if (read < 0) break
        total += read
    }
    return buffer.copyOf(total)
}

private fun logSourceDiag(
    url: String,
    headers: Map<String, String>,
    diagnosis: SourceFailureDiagnosis,
    status: Int?,
    contentType: String?,
    contentLength: String?,
    bytes: ByteArray,
    textual: Boolean,
    bodyText: String?
) {
    val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
    Log.w(
        PlayerRuntimeController.TAG,
        "SOURCE_DIAG reason=${diagnosis.reason} status=${status ?: "?"} " +
            "contentType=${contentType ?: "?"} len=${contentLength ?: "?"} host=$host"
    )
    val statusOkOrUnknown = status == null || status < 400
    if (!statusOkOrUnknown) return

    if (textual && bodyText != null) {
        Log.w(PlayerRuntimeController.TAG, "SOURCE_DIAG body: ${sanitizeBodySnippet(bodyText)}")
        Log.w(PlayerRuntimeController.TAG, "SOURCE_DIAG replay: ${buildCurlCommand(url, headers)}")
    } else if (bytes.isNotEmpty()) {
        Log.w(
            PlayerRuntimeController.TAG,
            "SOURCE_DIAG binary: hex=${hexPreview(bytes)} len=${contentLength ?: bytes.size.toString()}"
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL (deprecation warnings on unrelated lines are fine).

- [ ] **Step 3: Run the full player test package to confirm no regressions**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/player/SourceFailureDiagnostics.kt
git commit -m "Add network source-failure probe with SOURCE_DIAG logging" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Wire the probe into `onPlayerError` (async refine)

**Files:**
- Modify: `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt` (the `onPlayerError` final-error path, ~line 1332)

**Interfaces:**
- Consumes: `isContentShapedFailure` (Task 2), `probeSourceFailure` (Task 5), `SourceFailureDiagnosis.toDisplayMessage` (Task 4), `SourceFailureReason` (Task 1). Controller state: `currentStreamUrl`, `currentHeaders`, `scope`, `context`, `_uiState`. `PlaybackException.errorCodeName`.

- [ ] **Step 1: Add the async refine after the final-error update**

In `onPlayerError`, the final line currently reads (from the #1 fix):

```kotlin
                        _uiState.update { it.copy(error = error.toDisplayMessage(context), showLoadingOverlay = false, showPauseOverlay = false) }
```

Immediately AFTER that line (still inside `onPlayerError`, before the closing `}` of the method), insert:

```kotlin
                        // For content-shaped failures (3003 / parser IllegalState), the generic
                        // message above is shown immediately; then actively re-probe the source to
                        // refine it with a specific reason (error page / challenge / expired / …).
                        // Guarded so a superseding stream/retry doesn't get clobbered. See
                        // docs/superpowers/specs/2026-07-11-source-failure-diagnostic-probe-design.md.
                        if (isContentShapedFailure(error)) {
                            val probedUrl = currentStreamUrl
                            val probedHeaders = currentHeaders
                            val codeName = error.errorCodeName
                            scope.launch {
                                val diagnosis = probeSourceFailure(probedUrl, probedHeaders)
                                if (diagnosis.reason != SourceFailureReason.INCONCLUSIVE &&
                                    currentStreamUrl == probedUrl &&
                                    _uiState.value.error != null
                                ) {
                                    _uiState.update { it.copy(error = diagnosis.toDisplayMessage(context, codeName)) }
                                }
                            }
                        }
```

- [ ] **Step 2: Verify it compiles**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL. (If `scope.launch` needs an import, it is already used elsewhere in this file — no new import expected.)

- [ ] **Step 3: Run the full player test package**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git commit -m "Refine content-failure error message via async source probe" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Build, deploy to Shield, on-device verification

**Files:** none (verification only).

- [ ] **Step 1: Assemble the arm64 debug APK**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; export ANDROID_HOME=$HOME/Library/Android/sdk; export NUVIO_RELEASE_STORE_FILE=<scratchpad>/local-release.jks; ./gradlew :app:assembleFullDebug`
Expected: BUILD SUCCESSFUL. (Generate the throwaway keystore first if absent — see the shield-test-deploy-workflow memory: alias `nuviotv`, store/key pass `815787`.)

- [ ] **Step 2: Install on the Shield**

Run: `adb connect 10.0.0.166:5555 && adb -s 10.0.0.166:5555 install -r app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk`
Expected: `Success`.

- [ ] **Step 3: Arm logcat and reproduce a failure**

Run (background, fish-quoted): `adb -s 10.0.0.166:5555 logcat -c; adb -s 10.0.0.166:5555 logcat -v time "PlayerViewModel:V" "*:W" > <scratchpad>/source_diag.txt`
Then play a source known to be dead/expired (or wait for a real 3003). Confirm in the capture:
- A `SOURCE_DIAG reason=… status=… contentType=… host=…` line appears.
- For an error page: a `SOURCE_DIAG body:` snippet and `SOURCE_DIAG replay:` curl line appear.
- The on-screen error text refines from the generic message to the specific reason within a few seconds.

- [ ] **Step 4: Update the tracker**

In `ISSUES.md`, under #1, note that the active source-failure diagnostic probe (specific reasons + `SOURCE_DIAG` logging) landed, and reference the spec/plan. Commit:

```bash
git add ISSUES.md
git commit -m "docs(issues): note source-failure diagnostic probe landed" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Notes for the implementer

- The classifier, predicate, log helpers, and message mapping are the tested surface — keep logic there, keep Tasks 5–6 as thin wiring.
- Single-use debrid links may return 404/410 on the re-probe even when the original failure differed; the NOT_FOUND/EXPIRED copy is worded conservatively ("no longer available" / "expired") to stay honest.
- Truncated-file (varint/#4) probes return INCONCLUSIVE (first bytes look like valid video) → generic message + a `SOURCE_DIAG binary: hex=…` line. Tail-based truncation detection is deliberately out of scope (tracked in ISSUES.md #4).
