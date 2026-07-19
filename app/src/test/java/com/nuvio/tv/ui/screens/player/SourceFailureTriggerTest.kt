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
