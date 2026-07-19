package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
