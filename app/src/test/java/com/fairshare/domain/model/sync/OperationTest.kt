package com.fairshare.domain.model.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationTest {

    private val json = Json { encodeDefaults = true }

    private fun sampleExpenseOp(
        opId: String = "op-1",
        deviceId: String = "device-a",
        lamport: Long = 5,
    ): Operation = Operation(
        opId = opId,
        eventId = "evt-1",
        deviceId = deviceId,
        lamport = lamport,
        wallClockMs = 1_700_000_000_000L,
        payload = OpPayload.ExpenseUpsert(
            ExpenseSnapshot(
                id = "exp-1",
                eventId = "evt-1",
                title = "Pizza",
                amountCents = 2500,
                payerId = "p-1",
                date = 1_700_000_000_000L,
                shares = listOf(
                    ExpenseShareSnapshot(id = "s-1", participantId = "p-1", amountCents = 1250),
                    ExpenseShareSnapshot(id = "s-2", participantId = "p-2", amountCents = 1250),
                ),
                items = listOf(
                    ExpenseItemSnapshot(
                        id = "i-1",
                        label = "Margherita",
                        priceCents = 2500,
                        quantity = 1,
                        assignedTo = setOf("p-1", "p-2"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `operation round-trips through JSON without loss`() {
        val op = sampleExpenseOp()
        val encoded = json.encodeToString(Operation.serializer(), op)
        val decoded = json.decodeFromString(Operation.serializer(), encoded)
        assertEquals(op, decoded)
    }

    @Test
    fun `all payload variants round-trip`() {
        val payloads = listOf<OpPayload>(
            OpPayload.EventUpsert(
                EventSnapshot(id = "e", name = "Trip", currency = "EUR", createdAt = 0L),
            ),
            OpPayload.EventDelete(eventId = "e"),
            OpPayload.ParticipantUpsert(
                ParticipantSnapshot(id = "p", eventId = "e", name = "Alice"),
            ),
            OpPayload.ParticipantDelete(participantId = "p"),
            OpPayload.ExpenseUpsert(
                ExpenseSnapshot(
                    id = "x", eventId = "e", title = "t",
                    amountCents = 100, payerId = "p", date = 0L,
                ),
            ),
            OpPayload.ExpenseDelete(expenseId = "x"),
        )
        for (p in payloads) {
            val encoded = json.encodeToString(OpPayload.serializer(), p)
            val decoded = json.decodeFromString(OpPayload.serializer(), encoded)
            assertEquals(p, decoded)
        }
    }

    @Test
    fun `LwwOrder orders by lamport first`() {
        val low = sampleExpenseOp(lamport = 1)
        val high = sampleExpenseOp(lamport = 2)
        assertTrue(Operation.LwwOrder.compare(low, high) < 0)
        assertTrue(Operation.LwwOrder.compare(high, low) > 0)
    }

    @Test
    fun `LwwOrder breaks ties by deviceId lexicographically`() {
        val a = sampleExpenseOp(deviceId = "device-a", lamport = 7)
        val b = sampleExpenseOp(deviceId = "device-b", lamport = 7)
        assertTrue(Operation.LwwOrder.compare(a, b) < 0)
        assertTrue(Operation.LwwOrder.compare(b, a) > 0)
        assertEquals(0, Operation.LwwOrder.compare(a, a))
    }

    @Test
    fun `entityId and entityKind agree across upsert and delete`() {
        val upsert: OpPayload = OpPayload.ParticipantUpsert(
            ParticipantSnapshot(id = "p-42", eventId = "e", name = "Bob"),
        )
        val delete: OpPayload = OpPayload.ParticipantDelete(participantId = "p-42")
        assertEquals("p-42", upsert.entityId)
        assertEquals("p-42", delete.entityId)
        assertEquals(EntityKind.PARTICIPANT, upsert.entityKind)
        assertEquals(EntityKind.PARTICIPANT, delete.entityKind)
    }
}
