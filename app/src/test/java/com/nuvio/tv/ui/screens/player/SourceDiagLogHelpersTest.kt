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
