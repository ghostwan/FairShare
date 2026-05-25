package com.fairshare.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiKeyCodecTest {

    @Test fun `round-trip with model`() {
        val url = GeminiKeyCodec.encode("AIzaSy_some-key.with/symbols=", "gemini-2.5-flash")
        val decoded = GeminiKeyCodec.decode(url)
        assertEquals("AIzaSy_some-key.with/symbols=", decoded.key)
        assertEquals("gemini-2.5-flash", decoded.model)
    }

    @Test fun `round-trip without model`() {
        val url = GeminiKeyCodec.encode("k", null)
        val decoded = GeminiKeyCodec.decode(url)
        assertEquals("k", decoded.key)
        assertNull(decoded.model)
    }

    @Test fun `blank model is dropped`() {
        val url = GeminiKeyCodec.encode("k", "   ")
        val decoded = GeminiKeyCodec.decode(url)
        assertNull(decoded.model)
    }

    @Test fun `blank key rejected on encode`() {
        try {
            GeminiKeyCodec.encode(" ", null)
            fail("expected exception")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun `missing key rejected on decode`() {
        try {
            GeminiKeyCodec.decode("fairshare://gemini?model=foo")
            fail("expected exception")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun `recognises scheme`() {
        assertTrue(GeminiKeyCodec.isGeminiKeyUrl("fairshare://gemini?key=x"))
        assertFalse(GeminiKeyCodec.isGeminiKeyUrl("fairshare://join?event=x"))
        assertFalse(GeminiKeyCodec.isGeminiKeyUrl("https://fairshare-web-bdg.pages.dev/join?event=x"))
    }
}
