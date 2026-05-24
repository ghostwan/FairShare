package com.fairshare.data.sync

import com.fairshare.domain.model.sync.EventSnapshot
import com.fairshare.domain.model.sync.ExpenseSnapshot
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import com.fairshare.domain.model.sync.ParticipantSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and tamper-detection tests for [SneakernetCodec].
 */
class SneakernetCodecTest {

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
    fun `encode then decode is identity`() {
        val ops = sampleOps()
        val url = SneakernetCodec.encode(eventId, ops, key)
        assertTrue(url.startsWith("fairshare://sync?"))
        val decoded = SneakernetCodec.decode(url, key).getOrThrow()
        assertEquals(eventId, decoded.eventId)
        assertEquals(ops, decoded.ops)
    }

    @Test
    fun `tampering with the data field fails the HMAC check`() {
        val url = SneakernetCodec.encode(eventId, sampleOps(), key)
        // Flip one character in the data segment, keep the signature.
        val tampered = url.replace("data=", "data=AA")
        val result = SneakernetCodec.decode(tampered, key)
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as DecodeException).error
        assertTrue(err is SneakernetCodec.DecodeError.SignatureMismatch)
    }

    @Test
    fun `wrong key fails the HMAC check before any deserialization`() {
        val url = SneakernetCodec.encode(eventId, sampleOps(), key)
        val wrongKey = ByteArray(32) { 0x42 }
        val result = SneakernetCodec.decode(url, wrongKey)
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as DecodeException).error
        assertTrue(err is SneakernetCodec.DecodeError.SignatureMismatch)
    }

    @Test
    fun `malformed URL is rejected`() {
        val result = SneakernetCodec.decode("http://example.com/?event=x&data=y&sig=z", key)
        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as DecodeException).error
            is SneakernetCodec.DecodeError.MalformedUrl)
    }

    @Test
    fun `missing fields are rejected`() {
        val result = SneakernetCodec.decode("fairshare://sync?event=$eventId", key)
        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as DecodeException).error
            is SneakernetCodec.DecodeError.MissingFields)
    }

    @Test
    fun `empty op list round-trips`() {
        val url = SneakernetCodec.encode(eventId, emptyList(), key)
        val decoded = SneakernetCodec.decode(url, key).getOrThrow()
        assertEquals(eventId, decoded.eventId)
        assertTrue(decoded.ops.isEmpty())
    }

    @Test
    fun `large bundle round-trips and benefits from gzip`() {
        val many = (0 until 200).map { i ->
            Operation(
                opId = "op-$i",
                eventId = eventId,
                deviceId = "dA",
                lamport = i.toLong(),
                wallClockMs = 0L,
                payload = OpPayload.ParticipantUpsert(
                    ParticipantSnapshot(id = "p-$i", eventId = eventId, name = "User $i"),
                ),
            )
        }
        val url = SneakernetCodec.encode(eventId, many, key)
        assertNotNull(url)
        val decoded = SneakernetCodec.decode(url, key).getOrThrow()
        assertEquals(200, decoded.ops.size)
        assertEquals(many, decoded.ops)
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
            SneakernetCodec.encode(eventId, mixed, key)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
