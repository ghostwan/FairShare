package com.fairshare.data.local.dao

import com.fairshare.data.local.entity.ExpenseItemAssignmentEntity
import com.fairshare.data.local.entity.ExpenseItemEntity
import com.fairshare.data.local.entity.ExpenseShareEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseDetailsDiffTest {

    private fun share(pid: String, amount: Long, expenseId: String = "exp") =
        ExpenseShareEntity(expenseId = expenseId, participantId = pid, amountCents = amount)

    private fun item(id: String, label: String = "x", price: Long = 100, qty: Int = 1, pos: Int = 0) =
        ExpenseItemEntity(
            id = id, expenseId = "exp", label = label,
            priceCents = price, quantity = qty, position = pos,
        )

    // ---- shares ----

    @Test
    fun `shares diff classifies inserts updates deletes`() {
        val current = listOf(share("p1", 100), share("p2", 200), share("p3", 300))
        val incoming = listOf(
            share("p1", 100),    // unchanged
            share("p2", 250),    // amount changed
            share("p4", 400),    // new
        )

        val diff = ExpenseDetailsDiff.shares("exp", incoming, current)

        assertEquals(listOf(share("p4", 400)), diff.toInsert)
        assertEquals(listOf(share("p2", 250)), diff.toUpdate)
        assertEquals(listOf(share("p3", 300)), diff.toDelete)
    }

    @Test
    fun `shares diff is no-op when sets match exactly`() {
        val rows = listOf(share("p1", 100), share("p2", 200))
        val diff = ExpenseDetailsDiff.shares("exp", rows, rows)
        assertTrue(diff.toInsert.isEmpty())
        assertTrue(diff.toUpdate.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `shares diff rewrites expenseId on incoming rows`() {
        val incoming = listOf(share("p1", 100, expenseId = "wrong"))
        val diff = ExpenseDetailsDiff.shares("exp", incoming, emptyList())
        assertEquals("exp", diff.toInsert.single().expenseId)
    }

    // ---- items ----

    @Test
    fun `items diff inserts new updates changed deletes missing`() {
        val current = listOf(
            item("i1", label = "Pizza", price = 1000, pos = 0),
            item("i2", label = "Beer", price = 500, pos = 1),
            item("i3", label = "Tip", price = 100, pos = 2),
        )
        val incoming = listOf(
            item("i1", label = "Pizza", price = 1000),  // unchanged (will be at pos=0)
            item("i2", label = "Beer", price = 700),    // price changed (will be at pos=1)
            item("i4", label = "Coffee", price = 300),  // new (will be at pos=2)
        )

        val diff = ExpenseDetailsDiff.items("exp", incoming, current)

        assertEquals(1, diff.toInsert.size)
        assertEquals("i4", diff.toInsert.single().id)
        assertEquals(2, diff.toInsert.single().position)

        assertEquals(1, diff.toUpdate.size)
        assertEquals("i2", diff.toUpdate.single().id)
        assertEquals(700L, diff.toUpdate.single().priceCents)

        assertEquals(listOf("i3"), diff.toDeleteIds)
    }

    @Test
    fun `items diff treats a position-only change as an update`() {
        val current = listOf(item("i1", pos = 5))
        val incoming = listOf(item("i1", pos = 0))  // will be normalized to pos=0
        val diff = ExpenseDetailsDiff.items("exp", incoming, current)
        assertEquals(1, diff.toUpdate.size)
        assertEquals(0, diff.toUpdate.single().position)
    }

    // ---- assignments ----

    @Test
    fun `assignments diff adds missing and removes extras`() {
        val current = listOf(
            ExpenseItemAssignmentEntity(itemId = "i1", participantId = "p1"),
            ExpenseItemAssignmentEntity(itemId = "i1", participantId = "p2"),
        )
        val diff = ExpenseDetailsDiff.assignments(
            itemId = "i1",
            incomingParticipantIds = listOf("p2", "p3"),
            current = current,
        )

        assertEquals(setOf("p3"), diff.toInsert.map { it.participantId }.toSet())
        assertEquals(setOf("p1"), diff.toDelete.map { it.participantId }.toSet())
    }

    @Test
    fun `assignments diff is no-op when equal`() {
        val current = listOf(
            ExpenseItemAssignmentEntity(itemId = "i1", participantId = "p1"),
        )
        val diff = ExpenseDetailsDiff.assignments(
            itemId = "i1",
            incomingParticipantIds = listOf("p1"),
            current = current,
        )
        assertTrue(diff.toInsert.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }
}
