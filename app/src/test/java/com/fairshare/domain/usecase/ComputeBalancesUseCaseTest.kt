package com.fairshare.domain.usecase

import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.ExpenseShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputeBalancesUseCaseTest {

    private val useCase = ComputeBalancesUseCase()
    private val eventId = "evt-1"

    private fun expense(
        payer: String,
        amount: Long,
        isSettlement: Boolean = false,
    ) = Expense(
        eventId = eventId,
        title = "x",
        amountCents = amount,
        payerId = payer,
        shares = listOf(ExpenseShare(participantId = payer, amountCents = amount)),
        isSettlement = isSettlement,
    )

    @Test
    fun `totalsPaidBy aggregates per payer and excludes settlements`() {
        val totals = useCase.totalsPaidBy(
            listOf(
                expense(payer = "alice", amount = 1_000),
                expense(payer = "alice", amount = 2_500),
                expense(payer = "bob", amount = 4_200),
                // Settlement transfers are not actual expenses.
                expense(payer = "alice", amount = 9_999, isSettlement = true),
            )
        )
        assertEquals(3_500L, totals["alice"])
        assertEquals(4_200L, totals["bob"])
    }

    @Test
    fun `totalsPaidBy yields no entry for a participant who never paid`() {
        val totals = useCase.totalsPaidBy(
            listOf(expense(payer = "alice", amount = 1_000))
        )
        assertNull(totals["bob"])
    }

    @Test
    fun `totalsPaidBy on empty list is empty`() {
        assertEquals(emptyMap<String, Long>(), useCase.totalsPaidBy(emptyList()))
    }
}
