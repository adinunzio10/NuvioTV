package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import com.nuvio.tv.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for [toDisplayMessage] on the 3003
 * (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` / [UnrecognizedInputFormatException]) path.
 *
 * When ExoPlayer downloads the first bytes of a stream and none of the progressive container
 * extractors recognize it as video, it raises a 3003 whose raw message is the cryptic
 * "None of the available extractors (...) could read the stream." The player must surface the
 * friendly, actionable [R.string.player_error_source_invalid_content] "Try a different source"
 * text instead — see ISSUES.md #1. This locks the mapping that `onPlayerError` relies on.
 */
class PlaybackErrorDisplayMessageTest {

    private val friendlyInvalidContent = "FRIENDLY_INVALID_CONTENT"

    // PlaybackException's constructor reads SystemClock.elapsedRealtime(), which is unmocked
    // (and throws) on the plain JVM unit-test classpath. Stub it so we can build real exceptions.
    @Before
    fun stubSystemClock() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @After
    fun unstubSystemClock() {
        unmockkStatic(SystemClock::class)
    }

    private fun contextReturningFriendlyString(): Context = mockk<Context>().also { context ->
        every {
            context.getString(R.string.player_error_source_invalid_content, any())
        } returns friendlyInvalidContent
    }

    @Test
    fun `3003 caused by UnrecognizedInputFormatException maps to the friendly invalid-content string`() {
        val cause = UnrecognizedInputFormatException(
            "None of the available extractors (Mp4Extractor, MatroskaExtractor, ...) could read the stream.",
            mockk(relaxed = true)
        )
        val error = PlaybackException(
            "Source error",
            cause,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        )

        assertEquals(friendlyInvalidContent, error.toDisplayMessage(contextReturningFriendlyString()))
    }

    @Test
    fun `UnrecognizedInputFormatException nested deeper in the cause chain is still detected`() {
        val root = UnrecognizedInputFormatException(
            "None of the available extractors could read the stream.",
            mockk(relaxed = true)
        )
        val wrapped = IllegalStateException("wrapper", root)
        val error = PlaybackException(
            "Source error",
            wrapped,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        )

        assertEquals(friendlyInvalidContent, error.toDisplayMessage(contextReturningFriendlyString()))
    }
}
