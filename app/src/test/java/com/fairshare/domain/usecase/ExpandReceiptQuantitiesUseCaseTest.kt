package com.fairshare.domain.usecase

import com.fairshare.domain.model.ReceiptItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpandReceiptQuantitiesUseCaseTest {

    private val useCase = ExpandReceiptQuantitiesUseCase()

    @Test
    fun `disabled keeps the list as-is`() {
        val items = listOf(
            ReceiptItem("a", "Bière", 1100, quantity = 2),
            ReceiptItem("b", "Salade", 900, quantity = 1),
        )
        assertEquals(items, useCase(items, enabled = false))
    }

    @Test
    fun `enabled splits quantity items at unit price`() {
        val items = listOf(ReceiptItem("a", "Bière", 1100, quantity = 2))
        val out = useCase(items, enabled = true)
        assertEquals(2, out.size)
        assertEquals("Bière", out[0].label)
        assertEquals(550L, out[0].priceCents)
        assertEquals(1, out[0].quantity)
        assertEquals(550L, out[1].priceCents)
    }

    @Test
    fun `rounding remainder is distributed cent-by-cent`() {
        val items = listOf(ReceiptItem("a", "Truc", 1000, quantity = 3))
        val out = useCase(items, enabled = true)
        assertEquals(3, out.size)
        assertEquals(1000L, out.sumOf { it.priceCents })
        assertEquals(setOf(334L, 333L), out.map { it.priceCents }.toSet())
    }

    @Test
    fun `items with quantity 1 are passed through unchanged`() {
        val items = listOf(ReceiptItem("a", "Plat", 1850, quantity = 1))
        val out = useCase(items, enabled = true)
        assertEquals(items, out)
    }

    @Test
    fun `each expanded item gets its own id`() {
        val items = listOf(ReceiptItem("a", "Bière", 1100, quantity = 3))
        val out = useCase(items, enabled = true)
        assertEquals(3, out.map { it.id }.toSet().size)
    }
}
