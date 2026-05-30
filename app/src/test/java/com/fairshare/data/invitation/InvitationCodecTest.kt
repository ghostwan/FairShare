package com.fairshare.data.invitation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and decode-error tests for [InvitationCodec].
 *
 * The codec carries only `event` + `key`; the full op history is
 * pulled from the Worker by the joining device. Tests cover both URL
 * flavours and the typed decode errors the importer surfaces in the
 * UI.
 */
class InvitationCodecTest {

    private val eventId = "evt-1"
    private val key = ByteArray(32) { (it * 13 + 1).toByte() }

    @Test
    fun `encode then decode round-trips eventId and key (https default)`() {
        val url = InvitationCodec.encode(eventId, key)
        assertTrue(url.startsWith("https://fairshare-web-bdg.pages.dev/join?"))
        val decoded = InvitationCodec.decode(url).getOrThrow()
        assertEquals(eventId, decoded.eventId)
        assertTrue(decoded.eventKey.contentEquals(key))
    }

    @Test
    fun `encode emits the legacy custom scheme when requested`() {
        val url = InvitationCodec.encode(eventId, key, InvitationCodec.Host.Custom)
        assertTrue(url.startsWith("fairshare://join?"))
        val decoded = InvitationCodec.decode(url).getOrThrow()
        assertEquals(eventId, decoded.eventId)
        assertTrue(decoded.eventKey.contentEquals(key))
    }

    @Test
    fun `decode accepts the legacy custom scheme`() {
        val url = InvitationCodec.encode(eventId, key, InvitationCodec.Host.Custom)
        val decoded = InvitationCodec.decode(url).getOrThrow()
        assertEquals(eventId, decoded.eventId)
    }

    @Test
    fun `decode accepts any https host with join path`() {
        val url = "https://staging.example.com/join?event=$eventId&key=" +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val decoded = InvitationCodec.decode(url).getOrThrow()
        assertEquals(eventId, decoded.eventId)
    }

    @Test
    fun `URL stays small regardless of input`() {
        // The whole point of the new format: constant-size URL, well
        // under the QR-code byte-mode capacity at L (2953 bytes).
        val url = InvitationCodec.encode(eventId, key)
        assertTrue("URL too long: ${url.length}", url.length < 200)
    }

    @Test
    fun `decode rejects a malformed key length`() {
        val shortKey = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(16))
        val url = "fairshare://join?event=$eventId&key=$shortKey"
        val result = InvitationCodec.decode(url)
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as DecodeException).error
        assertTrue(err is InvitationCodec.DecodeError.MalformedUrl)
    }

    @Test
    fun `decode rejects a non-join URL`() {
        val result = InvitationCodec.decode("http://example.com/?event=x&key=y")
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as DecodeException).error
        assertTrue(err is InvitationCodec.DecodeError.MalformedUrl)
    }

    @Test
    fun `decode rejects missing fields`() {
        val result = InvitationCodec.decode("fairshare://join?event=$eventId")
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as DecodeException).error
        assertTrue(err is InvitationCodec.DecodeError.MissingFields)
    }

    @Test
    fun `encode rejects a non-32-byte key`() {
        var threw = false
        try {
            InvitationCodec.encode(eventId, ByteArray(16))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
