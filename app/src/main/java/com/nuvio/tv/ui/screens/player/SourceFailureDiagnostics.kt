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
