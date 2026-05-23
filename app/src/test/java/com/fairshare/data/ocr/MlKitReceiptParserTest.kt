package com.fairshare.data.ocr

import com.fairshare.data.ocr.MlKitReceiptParser.Companion.Token
import com.fairshare.data.ocr.MlKitReceiptParser.Companion.extractItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitReceiptParserTest {

    /** Builds tokens for a multi-line receipt. Each "line" is at a different Y. */
    private fun build(lines: List<List<Pair<String, Int>>>): List<Token> {
        val rowHeight = 30
        val tokens = mutableListOf<Token>()
        lines.forEachIndexed { rowIdx, row ->
            row.forEach { (text, x) ->
                tokens += Token(cx = x, cy = rowIdx * rowHeight + 15, height = 20, text = text)
            }
        }
        return tokens
    }

    @Test
    fun `single row with label and price on same line`() {
        val items = extractItems(build(listOf(
            listOf("Pizza" to 50, "Margherita" to 110, "12,50" to 600),
        )))
        assertEquals(1, items.size)
        assertEquals("Pizza Margherita", items[0].label)
        assertEquals(1250L, items[0].priceCents)
    }

    @Test
    fun `price far from label still detected via Y grouping`() {
        // ML Kit would put "Coca" and "3,50" in different Lines because of the big gap;
        // we still want them to be the same row.
        val items = extractItems(build(listOf(
            listOf("Coca" to 30, "Cola" to 80, "3,50" to 800),
        )))
        assertEquals(1, items.size)
        assertEquals("Coca Cola", items[0].label)
        assertEquals(350L, items[0].priceCents)
    }

    @Test
    fun `totals and payment lines are filtered`() {
        val items = extractItems(build(listOf(
            listOf("Salade" to 30, "8,90" to 700),
            listOf("Total" to 30, "TTC" to 80, "8,90" to 700),
            listOf("CB" to 30, "8,90" to 700),
            listOf("Rendu" to 30, "0,00" to 700),
        )))
        assertEquals(1, items.size)
        assertEquals("Salade", items[0].label)
    }

    @Test
    fun `quantity prefix is parsed as quantity metadata`() {
        val items = extractItems(build(listOf(
            listOf("2x" to 20, "Bière" to 80, "11,00" to 700),
            listOf("3 x" to 20, "Café" to 90, "6,00" to 700),
        )))
        assertEquals(2, items.size)
        assertEquals("Bière", items[0].label)
        assertEquals(1100L, items[0].priceCents)
        assertEquals(2, items[0].quantity)
        assertEquals("Café", items[1].label)
        assertEquals(600L, items[1].priceCents)
        assertEquals(3, items[1].quantity)
    }

    @Test
    fun `quantity stuck to label without space is handled`() {
        val items = extractItems(build(listOf(
            listOf("2xBière" to 80, "11,00" to 700),
        )))
        assertEquals(1, items.size)
        assertEquals("Bière", items[0].label)
        assertEquals(1100L, items[0].priceCents)
        assertEquals(2, items[0].quantity)
    }

    @Test
    fun `trailing quantity is handled (Salade x 2)`() {
        val items = extractItems(build(listOf(
            listOf("Salade" to 30, "x" to 100, "2" to 130, "12,00" to 700),
        )))
        assertEquals(1, items.size)
        assertEquals("Salade", items[0].label)
        assertEquals(1200L, items[0].priceCents)
        assertEquals(2, items[0].quantity)
    }

    @Test
    fun `price with euro symbol attached is parsed`() {
        val items = extractItems(build(listOf(
            listOf("Tarte" to 30, "Tatin" to 90, "7,50€" to 700),
        )))
        assertEquals(1, items.size)
        assertEquals(750L, items[0].priceCents)
    }

    @Test
    fun `realistic restaurant ticket`() {
        // Each row simulates what ML Kit yields for a real receipt.
        val items = extractItems(build(listOf(
            listOf("RESTAURANT" to 200, "LE" to 280, "PETIT" to 320, "BISTROT" to 400),
            listOf("12" to 30, "rue" to 60, "de" to 110, "Paris" to 150),
            listOf("Entrecôte" to 30, "frites" to 130, "19,50" to 700),
            listOf("Saumon" to 30, "grillé" to 100, "17,00" to 700),
            listOf("2x" to 20, "Verre" to 70, "de" to 120, "vin" to 150, "12,00" to 700),
            listOf("Café" to 30, "gourmand" to 100, "8,50" to 700),
            listOf("Sous-total" to 30, "57,00" to 700),
            listOf("TVA" to 30, "10%" to 80, "5,18" to 700),
            listOf("TOTAL" to 30, "TTC" to 90, "57,00" to 700),
            listOf("Espèces" to 30, "60,00" to 700),
            listOf("Rendu" to 30, "3,00" to 700),
        )))

        val labels = items.map { it.label }
        // "2x Verre de vin 12,00" stays as one item with quantity=2; expansion is now
        // performed by ExpandReceiptQuantitiesUseCase depending on user settings.
        assertEquals(
            listOf("Entrecôte frites", "Saumon grillé", "Verre de vin", "Café gourmand"),
            labels,
        )
        assertEquals(listOf(1950L, 1700L, 1200L, 850L), items.map { it.priceCents })
        assertEquals(listOf(1, 1, 2, 1), items.map { it.quantity })
    }

    @Test
    fun `negative discount is ignored`() {
        val items = extractItems(build(listOf(
            listOf("Pizza" to 30, "12,00" to 700),
            listOf("Remise" to 30, "-2,00" to 700),
        )))
        assertEquals(1, items.size)
        assertEquals("Pizza", items[0].label)
    }

    @Test
    fun `same item on two separate rows produces two items (intentional)`() {
        // Two distinct rows with the same content represent two real items
        // (e.g. waiter punched them in separately) — keep them both so they
        // can be assigned to different people.
        val items = extractItems(build(listOf(
            listOf("Coca" to 30, "3,50" to 700),
            listOf("Coca" to 30, "3,50" to 700),
        )))
        assertEquals(2, items.size)
    }

    @Test
    fun `row without recognizable price is skipped`() {
        val items = extractItems(build(listOf(
            listOf("Bonjour" to 30, "Madame" to 150),
            listOf("Burger" to 30, "9,90" to 700),
        )))
        assertEquals(1, items.size)
        assertEquals("Burger", items[0].label)
    }

    @Test
    fun `tokens slightly offset vertically still group as one row`() {
        // OCR jitter — Y centers differ by a few px within the same row.
        val tokens = listOf(
            Token(cx = 50, cy = 100, height = 20, text = "Burger"),
            Token(cx = 700, cy = 103, height = 20, text = "9,90"),
        )
        val items = extractItems(tokens)
        assertEquals(1, items.size)
        assertEquals("Burger", items[0].label)
        assertEquals(990L, items[0].priceCents)
    }

    @Test
    fun `empty input returns empty list`() {
        assertTrue(extractItems(emptyList()).isEmpty())
    }
}
