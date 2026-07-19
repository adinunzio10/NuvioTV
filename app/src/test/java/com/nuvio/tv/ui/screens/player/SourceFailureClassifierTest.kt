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
