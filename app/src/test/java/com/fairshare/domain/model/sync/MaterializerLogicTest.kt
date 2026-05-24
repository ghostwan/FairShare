package com.fairshare.domain.model.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property tests for the pure CRDT materializer.
 *
 * The persistent [com.fairshare.data.sync.OperationApplier] is a thin
 * orchestrator over Room DAOs, so the convergence guarantees we care
 * about (idempotency / commutativity / LWW / tombstones) are easier and
 * faster to assert here.
 *
 * Reference: DESIGN.md §9.
 */
class MaterializerLogicTest {

    private val eventId = "evt-1"

    private fun op(
        opId: String,
        deviceId: String,
        lamport: Long,
        payload: OpPayload,
    ): Operation = Operation(
        opId = opId,
        eventId = eventId,
        deviceId = deviceId,
        lamport = lamport,
        wallClockMs = 0L,
        payload = payload,
    )

    private fun participantUpsert(
        id: String,
        name: String,
        device: String,
        lamport: Long,
        opId: String = "op-$id-$device-$lamport",
    ): Operation = op(
        opId = opId,
        deviceId = device,
        lamport = lamport,
        payload = OpPayload.ParticipantUpsert(
            ParticipantSnapshot(id = id, eventId = eventId, name = name),
        ),
    )

    private fun participantDelete(
        id: String,
        device: String,
        lamport: Long,
        opId: String = "op-del-$id-$device-$lamport",
    ): Operation = op(
        opId = opId,
        deviceId = device,
        lamport = lamport,
        payload = OpPayload.ParticipantDelete(participantId = id),
    )

    private fun expenseUpsert(
        id: String,
        title: String,
        amountCents: Long,
        payerId: String,
        device: String,
        lamport: Long,
    ): Operation = op(
        opId = "op-$id-$device-$lamport",
        deviceId = device,
        lamport = lamport,
        payload = OpPayload.ExpenseUpsert(
            ExpenseSnapshot(
                id = id,
                eventId = eventId,
                title = title,
                amountCents = amountCents,
                payerId = payerId,
                date = 0L,
            ),
        ),
    )

    @Test
    fun `idempotency - applying the same op twice yields the same state`() {
        val o = participantUpsert("p1", "Alice", "dA", 1)
        val once = MaterializerLogic.resolve(listOf(o))
        val twice = MaterializerLogic.resolve(listOf(o, o))
        assertEquals(once, twice)
    }

    @Test
    fun `commutativity - reordering ops yields the same state`() {
        val ops = listOf(
            participantUpsert("p1", "Alice", "dA", 1),
            participantUpsert("p2", "Bob", "dB", 2),
            participantUpsert("p1", "Alicia", "dB", 3),
            expenseUpsert("e1", "Pizza", 2500, "p1", "dA", 4),
            participantDelete("p2", "dA", 5),
        )
        val forward = MaterializerLogic.resolve(ops)
        val backward = MaterializerLogic.resolve(ops.reversed())
        val shuffled = MaterializerLogic.resolve(ops.shuffled())
        assertEquals(forward, backward)
        assertEquals(forward, shuffled)
    }

    @Test
    fun `LWW - higher lamport wins`() {
        val low = participantUpsert("p1", "Alice", "dA", 1)
        val high = participantUpsert("p1", "Alicia", "dB", 5)
        val state = MaterializerLogic.resolve(listOf(low, high))
        assertEquals("Alicia", state.participants["p1"]?.name)
    }

    @Test
    fun `LWW tiebreaker - equal lamport, deviceId lexicographically larger wins`() {
        val a = participantUpsert("p1", "FromA", "dA", 7)
        val b = participantUpsert("p1", "FromB", "dB", 7)
        val state = MaterializerLogic.resolve(listOf(a, b))
        assertEquals("FromB", state.participants["p1"]?.name)
    }

    @Test
    fun `delete tombstones an earlier upsert`() {
        val upsert = participantUpsert("p1", "Alice", "dA", 1)
        val delete = participantDelete("p1", "dA", 2)
        val state = MaterializerLogic.resolve(listOf(upsert, delete))
        assertNull(state.participants["p1"])
    }

    @Test
    fun `late upsert revives a tombstoned entity`() {
        val upsert = participantUpsert("p1", "Alice", "dA", 1)
        val delete = participantDelete("p1", "dA", 2)
        val revive = participantUpsert("p1", "Alice-again", "dA", 3)
        val state = MaterializerLogic.resolve(listOf(upsert, delete, revive))
        assertEquals("Alice-again", state.participants["p1"]?.name)
    }

    @Test
    fun `concurrent emitters converge after exchanging ops`() {
        // Device A inserts p1 at lamport 1; Device B inserts p2 at lamport 1.
        // Then both observe the other op and rename their own at lamport 3.
        val deviceA = listOf(
            participantUpsert("p1", "A1", "dA", 1),
            participantUpsert("p1", "A1-renamed", "dA", 3),
        )
        val deviceB = listOf(
            participantUpsert("p2", "B1", "dB", 1),
            participantUpsert("p2", "B1-renamed", "dB", 3),
        )
        val merged = MaterializerLogic.resolve(deviceA + deviceB)
        assertEquals(2, merged.participants.size)
        assertEquals("A1-renamed", merged.participants["p1"]?.name)
        assertEquals("B1-renamed", merged.participants["p2"]?.name)
    }

    @Test
    fun `entities of different families are resolved independently`() {
        val ops = listOf(
            op(
                opId = "ev1",
                deviceId = "dA",
                lamport = 1,
                payload = OpPayload.EventUpsert(
                    EventSnapshot(id = eventId, name = "Trip", createdAt = 0L),
                ),
            ),
            participantUpsert("p1", "Alice", "dA", 2),
            expenseUpsert("e1", "Pizza", 1000, "p1", "dA", 3),
        )
        val state = MaterializerLogic.resolve(ops)
        assertNotNull(state.events[eventId])
        assertNotNull(state.participants["p1"])
        assertNotNull(state.expenses["e1"])
    }

    @Test
    fun `resolveEntity returns null for tombstoned entity`() {
        val ops = listOf(
            participantUpsert("p1", "Alice", "dA", 1),
            participantDelete("p1", "dB", 2),
        )
        val winner = MaterializerLogic.resolveEntity(EntityKind.PARTICIPANT, "p1", ops)
        assertNull(winner)
    }

    @Test
    fun `resolveEntity returns the winning upsert payload`() {
        val ops = listOf(
            participantUpsert("p1", "Alice", "dA", 1),
            participantUpsert("p1", "Alicia", "dB", 5),
        )
        val winner = MaterializerLogic.resolveEntity(EntityKind.PARTICIPANT, "p1", ops)
        assertTrue(winner is OpPayload.ParticipantUpsert)
        assertEquals("Alicia", (winner as OpPayload.ParticipantUpsert).participant.name)
    }

    @Test
    fun `expense LWW preserves whole snapshot of the winner`() {
        val older = expenseUpsert("e1", "Pizza", 1000, "p1", "dA", 1)
        val newer = expenseUpsert("e1", "Sushi", 3000, "p2", "dB", 2)
        val state = MaterializerLogic.resolve(listOf(older, newer))
        val winner = state.expenses["e1"]!!
        assertEquals("Sushi", winner.title)
        assertEquals(3000L, winner.amountCents)
        assertEquals("p2", winner.payerId)
    }

    @Test
    fun `stale EventDelete tombstone does not beat EventUpsert (resolve)`() {
        // Regression: EventDelete is never emitted by current code
        // (EventRepositoryImpl.delete is local-only). Older builds did
        // emit one and the residual op may still sit in the log on a
        // device that has since updated. Re-importing the event via a
        // JOIN bundle must re-materialize it.
        val upsert = op(
            opId = "ev-upsert",
            deviceId = "dA",
            lamport = 1,
            payload = OpPayload.EventUpsert(
                EventSnapshot(id = eventId, name = "Trip", currency = "EUR", createdAt = 0L),
            ),
        )
        val staleDelete = op(
            opId = "ev-delete-stale",
            deviceId = "dA",
            lamport = 99,
            payload = OpPayload.EventDelete(eventId = eventId),
        )
        val state = MaterializerLogic.resolve(listOf(upsert, staleDelete))
        assertNotNull(state.events[eventId])
        assertEquals("Trip", state.events[eventId]?.name)
    }

    @Test
    fun `stale EventDelete tombstone does not beat EventUpsert (resolveEntity)`() {
        val upsert = op(
            opId = "ev-upsert",
            deviceId = "dA",
            lamport = 1,
            payload = OpPayload.EventUpsert(
                EventSnapshot(id = eventId, name = "Trip", currency = "EUR", createdAt = 0L),
            ),
        )
        val staleDelete = op(
            opId = "ev-delete-stale",
            deviceId = "dA",
            lamport = 99,
            payload = OpPayload.EventDelete(eventId = eventId),
        )
        val winner = MaterializerLogic.resolveEntity(
            EntityKind.EVENT,
            eventId,
            listOf(upsert, staleDelete),
        )
        assertTrue(winner is OpPayload.EventUpsert)
        assertEquals("Trip", (winner as OpPayload.EventUpsert).event.name)
    }

    private fun categoryUpsert(
        id: String,
        name: String,
        device: String,
        lamport: Long,
        emoji: String = "🍕",
        color: Long = 0xFFEF6C00L,
    ): Operation = op(
        opId = "op-cat-$id-$device-$lamport",
        deviceId = device,
        lamport = lamport,
        payload = OpPayload.CategoryUpsert(
            CategorySnapshot(
                id = id,
                eventId = eventId,
                name = name,
                emoji = emoji,
                color = color,
            ),
        ),
    )

    private fun categoryDelete(id: String, device: String, lamport: Long): Operation = op(
        opId = "op-cat-del-$id-$device-$lamport",
        deviceId = device,
        lamport = lamport,
        payload = OpPayload.CategoryDelete(categoryId = id),
    )

    @Test
    fun `category LWW - higher lamport upsert wins`() {
        val low = categoryUpsert("c1", "Food", "dA", 1)
        val high = categoryUpsert("c1", "Groceries", "dB", 5)
        val state = MaterializerLogic.resolve(listOf(low, high))
        assertEquals("Groceries", state.categories["c1"]?.name)
    }

    @Test
    fun `category delete tombstones an earlier upsert`() {
        val ops = listOf(
            categoryUpsert("c1", "Food", "dA", 1),
            categoryDelete("c1", "dA", 2),
        )
        val state = MaterializerLogic.resolve(ops)
        assertNull(state.categories["c1"])
        assertNull(MaterializerLogic.resolveEntity(EntityKind.CATEGORY, "c1", ops))
    }

    @Test
    fun `category and expense are resolved independently`() {
        val ops = listOf(
            categoryUpsert("c1", "Food", "dA", 1),
            expenseUpsert("e1", "Pizza", 1000, "p1", "dA", 2),
        )
        val state = MaterializerLogic.resolve(ops)
        assertNotNull(state.categories["c1"])
        assertNotNull(state.expenses["e1"])
    }
}
