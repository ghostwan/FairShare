package com.fairshare.data.invitation

import com.fairshare.domain.model.sync.EventSnapshot
import com.fairshare.domain.model.sync.ExpenseSnapshot
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import com.fairshare.domain.model.sync.ParticipantSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and tamper-detection tests for [InvitationCodec].
 */
class InvitationCodecTest {

    private val eventId = "evt-1"
    private val key = ByteArray(32) { (it * 13 + 1).toByte() }

    private fun sampleOps(): List<Operation> = listOf(
        Operation(
            opId = "op-1",
            eventId = eventId,
            deviceId = "dA",
            lamport = 1,
            wallClockMs = 1_700_000_000_000L,
            payload = OpPayload.EventUpsert(
                EventSnapshot(id = eventId, name = "Roadtrip", createdAt = 0L),
            ),
        ),
        Operation(
            opId = "op-2",
            eventId = eventId,
            deviceId = "dA",
            lamport = 2,
            wallClockMs = 1_700_000_000_000L,
            payload = OpPayload.ParticipantUpsert(
                ParticipantSnapshot(id = "p1", eventId = eventId, name = "Alice"),
            ),
        ),
        Operation(
            opId = "op-3",
            eventId = eventId,
            deviceId = "dB",
            lamport = 3,
            wallClockMs = 1_700_000_000_000L,
            payload = OpPayload.ExpenseUpsert(
                ExpenseSnapshot(
                    id = "e1", eventId = eventId, title = "Pizza",
                    amountCents = 2500, payerId = "p1", date = 0L,
                ),
            ),
        ),
    )

    @Test
    fun `encode then decode round-trips key and ops`() {
        val ops = sampleOps()
        val url = InvitationCodec.encode(eventId, ops, key)
        assertTrue(url.startsWith("https://fairshare-web-bdg.pages.dev/join?"))
        val decoded = InvitationCodec.decode(url).getOrThrow()
        assertEquals(eventId, decoded.eventId)
        assertTrue(decoded.eventKey.contentEquals(key))
        assertEquals(ops, decoded.ops)
    }

    @Test
    fun `encode emits the legacy custom scheme when requested`() {
        val ops = sampleOps()
        val url = InvitationCodec.encode(eventId, ops, key, InvitationCodec.Host.Custom)
        assertTrue(url.startsWith("fairshare://join?"))
        val decoded = InvitationCodec.decode(url).getOrThrow()
        assertEquals(eventId, decoded.eventId)
        assertEquals(ops, decoded.ops)
    }

    @Test
    fun `decode accepts the legacy custom scheme`() {
        val ops = sampleOps()
        val url = InvitationCodec.encode(eventId, ops, key, InvitationCodec.Host.Custom)
        val decoded = InvitationCodec.decode(url).getOrThrow()
        assertEquals(ops, decoded.ops)
    }

    @Test
    fun `decode rejects a tampered seed`() {
        val url = InvitationCodec.encode(eventId, sampleOps(), key)
        val tampered = url.replace("seed=", "seed=AA")
        val result = InvitationCodec.decode(tampered)
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as DecodeException).error
        assertTrue(err is InvitationCodec.DecodeError.SignatureMismatch)
    }

    @Test
    fun `decode rejects a tampered embedded key`() {
        // Swapping the key invalidates the HMAC (mac key is derived
        // from the event key), so the bundle is rejected before
        // attempting to deserialize the seed.
        val url = InvitationCodec.encode(eventId, sampleOps(), key)
        val otherKey = ByteArray(32) { (it + 1).toByte() }
        val otherEncoded = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(otherKey)
        val tampered = url.replace(Regex("key=[^&]+"), "key=$otherEncoded")
        val result = InvitationCodec.decode(tampered)
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as DecodeException).error
        assertTrue(err is InvitationCodec.DecodeError.SignatureMismatch)
    }

    @Test
    fun `decode rejects a malformed key length`() {
        val url = InvitationCodec.encode(eventId, sampleOps(), key)
        val shortKey = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(16))
        val tampered = url.replace(Regex("key=[^&]+"), "key=$shortKey")
        val result = InvitationCodec.decode(tampered)
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as DecodeException).error
        assertTrue(err is InvitationCodec.DecodeError.MalformedUrl)
    }

    @Test
    fun `decode rejects a non-join URL`() {
        val result = InvitationCodec.decode("http://example.com/?event=x&key=y&seed=z&sig=w")
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
            InvitationCodec.encode(eventId, sampleOps(), ByteArray(16))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `encode rejects ops from a different event`() {
        val mixed = sampleOps() + Operation(
            opId = "stray",
            eventId = "other-event",
            deviceId = "dA",
            lamport = 99,
            wallClockMs = 0L,
            payload = OpPayload.EventDelete(eventId = "other-event"),
        )
        var threw = false
        try {
            InvitationCodec.encode(eventId, mixed, key)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
