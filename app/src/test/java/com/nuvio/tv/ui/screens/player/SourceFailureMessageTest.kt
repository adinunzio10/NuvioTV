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
