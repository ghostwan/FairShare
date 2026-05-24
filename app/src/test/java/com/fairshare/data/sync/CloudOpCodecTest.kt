package com.fairshare.data.sync

import com.fairshare.domain.model.sync.EventSnapshot
import com.fairshare.domain.model.sync.ExpenseSnapshot
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Round-trip + tamper-detection tests for [CloudOpCodec]. The codec is
 * the bridge between in-memory [Operation] values and the opaque
 * `(nonce, ciphertext)` blobs the Worker stores; both halves of the
 * trip must be lossless and authenticated.
 */
class CloudOpCodecTest {

    private val eventId = "evt-1"
    private val key = ByteArray(32) { (it * 3 + 1).toByte() }

    private fun sampleEventOp() = Operation(
        opId = "11111111-1111-1111-1111-111111111111",
        eventId = eventId,
        deviceId = "device-A",
        lamport = 42L,
        wallClockMs = 1_700_000_000_000L,
        payload = OpPayload.EventUpsert(
            EventSnapshot(id = eventId, name = "Roadtrip", currency = "EUR", createdAt = 0L),
        ),
    )

    private fun sampleExpenseOp() = Operation(
        opId = "22222222-2222-2222-2222-222222222222",
        eventId = eventId,
        deviceId = "device-B",
        lamport = 99L,
        wallClockMs = 1_700_000_001_000L,
        payload = OpPayload.ExpenseUpsert(
            ExpenseSnapshot(
                id = "exp-1",
                eventId = eventId,
                title = "Dinner",
                amountCents = 4_200L,
                payerId = "p-1",
                date = 1_700_000_000_000L,
                shares = emptyList(),
                items = emptyList(),
            ),
        ),
    )

    @Test
    fun `event op round-trips losslessly`() {
        val op = sampleEventOp()
        val enc = CloudOpCodec.encrypt(op, key)
        assertEquals(op.opId, enc.opId)
        assertEquals(op.lamport, enc.lamport)
        assertEquals(op.deviceId, enc.deviceId)
        assertEquals(SyncCrypto.GCM_NONCE_LEN, enc.nonce.size)
        val decoded = CloudOpCodec.decrypt(enc, eventId, key)
        assertEquals(op, decoded)
    }

    @Test
    fun `expense op with nested snapshot round-trips`() {
        val op = sampleExpenseOp()
        val decoded = CloudOpCodec.decrypt(CloudOpCodec.encrypt(op, key), eventId, key)
        assertEquals(op, decoded)
    }

    @Test
    fun `successive encrypts use fresh nonces`() {
        val op = sampleEventOp()
        val a = CloudOpCodec.encrypt(op, key)
        val b = CloudOpCodec.encrypt(op, key)
        assertNotEquals(a.nonce.toList(), b.nonce.toList())
        assertNotEquals(a.ciphertext.toList(), b.ciphertext.toList())
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun `decrypt with wrong key throws`() {
        val enc = CloudOpCodec.encrypt(sampleEventOp(), key)
        val wrong = ByteArray(32) { 7 }
        CloudOpCodec.decrypt(enc, eventId, wrong)
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun `decrypt rejects tampered ciphertext`() {
        val enc = CloudOpCodec.encrypt(sampleEventOp(), key)
        enc.ciphertext[0] = (enc.ciphertext[0].toInt() xor 0x01).toByte()
        CloudOpCodec.decrypt(enc, eventId, key)
    }
}
