package com.fairshare.domain.usecase

import com.fairshare.domain.model.Category
import com.fairshare.domain.model.DefaultCategories
import com.fairshare.domain.model.Expense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeCategoryStatsUseCaseTest {

    private val useCase = ComputeCategoryStatsUseCase()
    private val eventId = "evt-1"

    private fun expense(
        amount: Long,
        categoryId: String? = null,
        isSettlement: Boolean = false,
    ) = Expense(
        eventId = eventId,
        title = "x",
        amountCents = amount,
        payerId = "p1",
        categoryId = categoryId,
        isSettlement = isSettlement,
    )

    @Test
    fun `empty list yields empty stats`() {
        assertTrue(useCase(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `groups by default category and computes fractions`() {
        val stats = useCase(
            expenses = listOf(
                expense(1000, DefaultCategories.FOOD.id),
                expense(3000, DefaultCategories.FOOD.id),
                expense(6000, DefaultCategories.TRANSPORT.id),
            ),
            customCategories = emptyList(),
        )
        assertEquals(2, stats.size)
        // Transport (6000) is bigger so it comes first.
        assertEquals(DefaultCategories.TRANSPORT.id, stats[0].category?.id)
        assertEquals(6000L, stats[0].totalCents)
        assertEquals(0.6, stats[0].fraction, 1e-9)
        assertEquals(DefaultCategories.FOOD.id, stats[1].category?.id)
        assertEquals(4000L, stats[1].totalCents)
        assertEquals(2, stats[1].count)
        assertEquals(0.4, stats[1].fraction, 1e-9)
    }

    @Test
    fun `settlements are excluded from the aggregation`() {
        val stats = useCase(
            expenses = listOf(
                expense(1000, DefaultCategories.FOOD.id),
                expense(99_999, isSettlement = true),
            ),
            customCategories = emptyList(),
        )
        assertEquals(1, stats.size)
        assertEquals(1000L, stats[0].totalCents)
        assertEquals(1.0, stats[0].fraction, 1e-9)
    }

    @Test
    fun `null categoryId lands in the uncategorized bucket`() {
        val stats = useCase(
            expenses = listOf(
                expense(1000),
                expense(3000),
            ),
            customCategories = emptyList(),
        )
        assertEquals(1, stats.size)
        assertNull(stats[0].category)
        assertEquals(4000L, stats[0].totalCents)
    }

    @Test
    fun `unknown id collapses into the uncategorized bucket`() {
        // 'ghost-uuid' is neither a default nor a known custom category
        // (eg. the custom category was deleted on another device).
        val stats = useCase(
            expenses = listOf(
                expense(1000, "ghost-uuid"),
                expense(500, null),
            ),
            customCategories = emptyList(),
        )
        assertEquals(1, stats.size)
        assertNull(stats[0].category)
        assertEquals(1500L, stats[0].totalCents)
    }

    @Test
    fun `custom categories are resolved by id`() {
        val custom = Category(
            id = "cust-1",
            eventId = eventId,
            name = "Souvenirs",
            emoji = "🎁",
            color = 0xFFAA00AA,
        )
        val stats = useCase(
            expenses = listOf(expense(2500, "cust-1")),
            customCategories = listOf(custom),
        )
        assertEquals(1, stats.size)
        assertEquals("cust-1", stats[0].category?.id)
        assertEquals("Souvenirs", stats[0].category?.name)
    }
}
