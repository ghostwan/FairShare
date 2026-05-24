package com.fairshare.data.sync

import com.fairshare.domain.model.sync.EventSnapshot
import com.fairshare.domain.model.sync.ExpenseSnapshot
import com.fairshare.domain.model.sync.MaterializerLogic
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import com.fairshare.domain.model.sync.ParticipantSnapshot
import com.fairshare.domain.repository.CloudTransport
import com.fairshare.domain.repository.CloudTransport.EncryptedOp
import com.fairshare.domain.repository.CloudTransport.PullResult
import com.fairshare.domain.repository.CloudTransport.PushResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * End-to-end convergence tests for the cloud-transport pipeline.
 *
 * Instead of pulling in Room + DataStore to exercise the real
 * [SyncCoordinator] (which would push these out of the JVM unit-test
 * source set), the tests stand up the *transport* slice directly: two
 * "devices", each with an in-memory op log, sharing a single
 * [InMemoryCloudTransport] that mimics the Worker's
 * `INSERT OR IGNORE` + `lamport > since` semantics.
 *
 * What this asserts:
 *
 *   - The wire format round-trips: encrypted bytes pushed by device A
 *     decrypt successfully on device B with the same key.
 *   - The pull cursor convergence: a device that has caught up to
 *     `MAX(lamport, origin=CLOUD)` and then pulls again receives no
 *     duplicates.
 *   - Materialized state convergence: feeding the merged op set
 *     through [MaterializerLogic] on each device produces byte-equal
 *     [EventSnapshot] / [ParticipantSnapshot] / [ExpenseSnapshot]
 *     maps, even when ops were produced in interleaved order.
 *
 * The materializer convergence guarantee itself is already covered by
 * `MaterializerLogicTest`; this file proves that the cloud transport
 * does not violate it.
 */
class SyncCoordinatorConvergenceTest {

    /** Per-event in-memory store, mimicking the D1 table on the Worker. */
    private class InMemoryCloudTransport : CloudTransport {
        private data class Row(val opId: String, val lamport: Long, val deviceId: String, val nonce: ByteArray, val ciphertext: ByteArray)

        // Map<eventId, Map<opId, Row>>. opId is the dedup key just like
        // the Worker's INSERT OR IGNORE on (event_id, op_id).
        private val store = mutableMapOf<String, MutableMap<String, Row>>()
        private val mutex = Mutex()

        override suspend fun push(
            eventId: String,
            bearer: String,
            ops: List<EncryptedOp>,
        ): Result<PushResult> = mutex.withLock {
            val bucket = store.getOrPut(eventId) { mutableMapOf() }
            var inserted = 0
            for (op in ops) {
                if (!bucket.containsKey(op.opId)) {
                    bucket[op.opId] = Row(op.opId, op.lamport, op.deviceId, op.nonce, op.ciphertext)
                    inserted++
                }
            }
            Result.success(PushResult(inserted))
        }

        override suspend fun pull(
            eventId: String,
            bearer: String,
            since: Long,
        ): Result<PullResult> = mutex.withLock {
            val rows = store[eventId].orEmpty().values
                .filter { it.lamport > since }
                .sortedWith(compareBy({ it.lamport }, { it.opId }))
            val ops = rows.map { EncryptedOp(it.opId, it.lamport, it.deviceId, it.nonce, it.ciphertext) }
            val nextSince = ops.maxOfOrNull { it.lamport } ?: since
            Result.success(PullResult(ops, nextSince, hasMore = false))
        }
    }

    /**
     * One simulated device. Holds its own copy of the op log + a
     * device id + a Lamport clock. Push/pull go through the shared
     * [transport]; encryption uses the per-event AES-GCM sub-key.
     */
    private class Device(
        val deviceId: String,
        val eventKey: ByteArray,
        val transport: InMemoryCloudTransport,
    ) {
        private val cipherKey = SyncCrypto.deriveCloudCipherKey(eventKey)
        private val bearer = SyncCrypto.computeWorkerBearer(eventKey, EVENT_ID)
        val log = mutableListOf<Operation>()
        var lamport: Long = 0L

        /** Emit a new op locally and append to the local log. */
        fun emit(payload: OpPayload): Operation {
            lamport++
            val op = Operation(
                opId = UUID.randomUUID().toString(),
                eventId = EVENT_ID,
                deviceId = deviceId,
                lamport = lamport,
                wallClockMs = 0L,
                payload = payload,
            )
            log.add(op)
            return op
        }

        /** Push every op not yet on the transport (idempotent). */
        suspend fun push() {
            val pushedIds = HashSet<String>() // tiny: we just push everything; dedup is server-side
            val enc = log.map { CloudOpCodec.encrypt(it, cipherKey) }
            transport.push(EVENT_ID, bearer, enc).getOrThrow()
            pushedIds.size // silence unused
        }

        /** Pull, decrypt, observe lamport, dedup into the local log. */
        suspend fun pull() {
            val cloudMax = log.filter { it.deviceId != deviceId }.maxOfOrNull { it.lamport } ?: 0L
            val result = transport.pull(EVENT_ID, bearer, cloudMax).getOrThrow()
            val known = log.map { it.opId }.toHashSet()
            for (enc in result.ops) {
                if (enc.opId in known) continue
                val op = CloudOpCodec.decrypt(enc, EVENT_ID, cipherKey)
                // Mimic LamportClockLogic.merge so a subsequent local
                // emit is strictly greater than anything observed.
                lamport = maxOf(lamport, op.lamport) + 1 - 1
                if (op.lamport >= lamport) lamport = op.lamport
                log.add(op)
            }
        }
    }

    private companion object {
        const val EVENT_ID = "00000000-0000-0000-0000-00000000abcd"
    }

    private fun newPair(): Triple<InMemoryCloudTransport, Device, Device> {
        val transport = InMemoryCloudTransport()
        val key = ByteArray(32) { (it * 17 + 5).toByte() }
        val a = Device("device-A", key, transport)
        val b = Device("device-B", key, transport)
        return Triple(transport, a, b)
    }

    @Test
    fun `device A push is visible on device B after pull`() = runTest {
        val (_, a, b) = newPair()
        a.emit(OpPayload.EventUpsert(EventSnapshot(id = EVENT_ID, name = "Roadtrip", createdAt = 0L)))
        a.emit(OpPayload.ParticipantUpsert(ParticipantSnapshot(id = "p1", eventId = EVENT_ID, name = "Alice")))
        a.push()

        b.pull()

        // Both logs now hold the same op-id set.
        assertEquals(a.log.map { it.opId }.toSet(), b.log.map { it.opId }.toSet())
        // Materialized state matches exactly.
        val sa = MaterializerLogic.resolve(a.log)
        val sb = MaterializerLogic.resolve(b.log)
        assertEquals(sa.events, sb.events)
        assertEquals(sa.participants, sb.participants)
    }

    @Test
    fun `pull is idempotent and never re-applies the same op`() = runTest {
        val (_, a, b) = newPair()
        a.emit(OpPayload.EventUpsert(EventSnapshot(id = EVENT_ID, name = "Trip", createdAt = 0L)))
        a.push()

        b.pull()
        val sizeAfterFirst = b.log.size
        repeat(5) { b.pull() }
        assertEquals(sizeAfterFirst, b.log.size)
    }

    @Test
    fun `concurrent edits on different entities both survive`() = runTest {
        val (_, a, b) = newPair()
        // Bootstrap a shared event so both devices share an entity tree.
        a.emit(OpPayload.EventUpsert(EventSnapshot(id = EVENT_ID, name = "Trip", createdAt = 0L)))
        a.push()
        b.pull()

        // Both devices add their own participant without seeing each other.
        a.emit(OpPayload.ParticipantUpsert(ParticipantSnapshot(id = "p-alice", eventId = EVENT_ID, name = "Alice")))
        b.emit(OpPayload.ParticipantUpsert(ParticipantSnapshot(id = "p-bob", eventId = EVENT_ID, name = "Bob")))

        a.push(); b.push()
        a.pull(); b.pull()

        val sa = MaterializerLogic.resolve(a.log)
        val sb = MaterializerLogic.resolve(b.log)
        assertEquals(sa.participants, sb.participants)
        assertEquals(setOf("p-alice", "p-bob"), sa.participants.keys)
    }

    @Test
    fun `concurrent edits on same entity converge to higher lamport`() = runTest {
        val (_, a, b) = newPair()
        a.emit(OpPayload.EventUpsert(EventSnapshot(id = EVENT_ID, name = "Original", createdAt = 0L)))
        a.push()
        b.pull()

        // Both devices rename the event without seeing each other. Both
        // lamports tick to 2 locally; the deviceId tiebreak picks one
        // deterministically.
        a.emit(OpPayload.EventUpsert(EventSnapshot(id = EVENT_ID, name = "A's name", createdAt = 0L)))
        b.emit(OpPayload.EventUpsert(EventSnapshot(id = EVENT_ID, name = "B's name", createdAt = 0L)))

        a.push(); b.push()
        a.pull(); b.pull()

        val sa = MaterializerLogic.resolve(a.log)
        val sb = MaterializerLogic.resolve(b.log)
        assertEquals(sa.events, sb.events)
        // Deterministic winner: device-B > device-A lexicographically,
        // so at equal lamport B wins.
        assertEquals("B's name", sa.events.getValue(EVENT_ID).name)
    }

    @Test
    fun `multi-round sync converges with interleaved emits`() = runTest {
        val (_, a, b) = newPair()
        a.emit(OpPayload.EventUpsert(EventSnapshot(id = EVENT_ID, name = "Trip", createdAt = 0L)))
        a.push(); b.pull()

        repeat(10) { i ->
            val origin = if (i % 2 == 0) a else b
            origin.emit(
                OpPayload.ExpenseUpsert(
                    ExpenseSnapshot(
                        id = "exp-$i",
                        eventId = EVENT_ID,
                        title = "expense $i",
                        amountCents = (i + 1) * 100L,
                        payerId = "p1",
                        date = 0L,
                        shares = emptyList(),
                        items = emptyList(),
                    ),
                ),
            )
            // Sync after each emit, alternating direction.
            a.push(); b.push()
            a.pull(); b.pull()
        }

        val sa = MaterializerLogic.resolve(a.log)
        val sb = MaterializerLogic.resolve(b.log)
        assertEquals(10, sa.expenses.size)
        assertEquals(sa.events, sb.events)
        assertEquals(sa.expenses, sb.expenses)
    }

    @Test
    fun `wrong device key cannot decrypt and skips the op cleanly`() = runTest {
        val transport = InMemoryCloudTransport()
        val keyA = ByteArray(32) { 1 }
        val keyB = ByteArray(32) { 2 }
        val a = Device("device-A", keyA, transport)
        val b = Device("device-B", keyB, transport)

        a.emit(OpPayload.EventUpsert(EventSnapshot(id = EVENT_ID, name = "private", createdAt = 0L)))
        a.push()

        // device-B uses a different cipher key, so its bearer is also
        // different — the InMemoryCloudTransport doesn't enforce
        // bearer auth (we test that with MockWebServer), but we want
        // to assert the AEAD failure path on decrypt.
        var thrown: Throwable? = null
        try {
            b.pull()
        } catch (t: javax.crypto.AEADBadTagException) {
            thrown = t
        } catch (t: javax.crypto.BadPaddingException) {
            thrown = t
        }
        assertNotNull("expected AEAD failure when decrypting with the wrong key", thrown)
        assertTrue(b.log.isEmpty())
    }
}
