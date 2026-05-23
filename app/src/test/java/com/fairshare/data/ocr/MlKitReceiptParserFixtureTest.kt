package com.fairshare.data.ocr

import com.fairshare.data.ocr.MlKitReceiptParser.Companion.Token
import com.fairshare.data.ocr.MlKitReceiptParser.Companion.extractItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests built from real-world OCR dumps captured on-device.
 *
 * Each fixture is a `ReceiptOCR` logcat dump (see app/docs/bug-receipts/README.md).
 * Lines follow the format `... I ReceiptOCR: <text>|<cx>|<cy>|<height>` between the
 * `=== BEGIN` / `=== END` markers.
 */
class MlKitReceiptParserFixtureTest {

    private fun loadFixture(name: String): List<Token> {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("receipts/$name")) {
            "Fixture not found: receipts/$name"
        }
        val tokens = mutableListOf<Token>()
        var inside = false
        stream.bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            when {
                line.contains("=== BEGIN receipt dump") -> inside = true
                line.contains("=== END receipt dump") -> inside = false
                inside -> {
                    // Drop the logcat prefix "... I ReceiptOCR: "
                    val payload = line.substringAfter("ReceiptOCR:", missingDelimiterValue = "").trim()
                    if (payload.isEmpty()) return@forEachLine
                    val parts = payload.split('|')
                    if (parts.size != 4) return@forEachLine
                    val cx = parts[1].toIntOrNull() ?: return@forEachLine
                    val cy = parts[2].toIntOrNull() ?: return@forEachLine
                    val h = parts[3].toIntOrNull() ?: return@forEachLine
                    tokens += Token(cx = cx, cy = cy, height = h, text = parts[0])
                }
            }
        }
        return tokens
    }

    /**
     * Bug 01 — single-link clustering bridged 5 different rows into one because the
     * right-most price column is slightly Y-offset from the left one. Expected:
     * each item line of the receipt becomes its own ReceiptItem.
     */
    @Test
    fun `bug-01 merged lines — every item line becomes a separate ReceiptItem`() {
        val tokens = loadFixture("bug-01-merged-lines.log")
        assertEquals("fixture should contain 143 tokens", 143, tokens.size)

        val items = extractItems(tokens)

        // The receipt has these distinct visual lines (in order):
        //   1× COMPLETE CHORIZO              10,50
        //   1× SUPP ING 3E                    3,00
        //   1× COMPLETE CHORIZO              10,50
        //   1× SUPP ING 3E                    3,00   (cx-reversed by OCR)
        //   1× COMPLETE CHORIZO              10,50
        //   1× COMPLETE CHORIZO              10,50   ("T" instead of "1" from OCR)
        //   1× LA BRETONNE                   14,00
        //   1× LA BRETONNE                   14,00
        //   1× 50CL CIDRE BRUT                7,50
        //   1× BEURRE SUCRE                   3,50
        //   1× BEURRE SUCRE                   3,50
        //   1× CREME DE MARRON                5,00
        //   1× CREME DE MARRON                5,00
        //   1× SUPP CHANTILLY                 2,00
        //   1× SPECIAL SPECULOS               9,00
        //   1× NUTELLA                        5,00
        //   1× EXPRESSO                       2,00
        // = 17 lines. We tolerate a couple of mis-parses (header line, weird OCR
        // garbage) but must be at least 12.
        assertTrue(
            "Expected at least 12 distinct items, got ${items.size}: ${items.map { it.label }}",
            items.size >= 12,
        )

        // No single item should swallow several lines' worth of price — every
        // detected price must match one we see on the ticket.
        val knownPrices = setOf(1050L, 300L, 1400L, 750L, 350L, 500L, 200L, 900L)
        items.forEach { item ->
            assertTrue(
                "Unexpected price ${item.priceCents} for label '${item.label}'",
                item.priceCents in knownPrices,
            )
        }

        // The "COMPLETE CHORIZO" line should appear several times; never as
        // "1 1 1 1 1 COMPLETE" (the original bug signature).
        val mergedSignature = items.firstOrNull {
            Regex("""(?:\b1\b\s+){3,}""").containsMatchIn(it.label)
        }
        assertEquals(
            "Found a merged-row item that should have been split: ${mergedSignature?.label}",
            null,
            mergedSignature,
        )

        // At least one item must be a CHORIZO at 10,50.
        val chorizo = items.firstOrNull {
            it.label.contains("CHORIZO", ignoreCase = true) && it.priceCents == 1050L
        }
        assertNotNull("No CHORIZO @ 10,50 item found", chorizo)
    }

    /**
     * Second capture of the same receipt, after the initial anchor-clustering fix
     * landed but before the duplicate-price-column filter. The dump exposes the
     * fact that the printer emits TWO aligned price columns (EUR/U and EUR) whose
     * cy is slightly offset, which used to swallow neighbouring rows. After the
     * second fix we expect a clean parse with all the expected line items.
     */
    @Test
    fun `bug-01 two price columns — receipt parses to the expected items`() {
        val tokens = loadFixture("bug-01-two-price-columns.log")
        val items = extractItems(tokens)

        // Every item line of the receipt should produce its own ReceiptItem.
        // We accept a small amount of slack for OCR noise (header, footer, …).
        assertTrue(
            "Expected ≥ 15 items, got ${items.size}: ${items.map { "${it.label}=${it.priceCents}" }}",
            items.size >= 15,
        )

        // Sum of all detected item prices must match the printed sub-total (118,50 €).
        val total = items.sumOf { it.priceCents * it.quantity }
        assertEquals("Sum of items should equal the printed sub-total", 11850L, total)

        // Sanity check on the COMPLETE CHORIZO lines: 4 of them at 10,50 each.
        val chorizos = items.filter { it.label.contains("CHORIZO", ignoreCase = true) }
        assertEquals("Expected 4 CHORIZO lines, got ${chorizos.size}", 4, chorizos.size)
        assertTrue(
            "Every CHORIZO line should be priced 10,50",
            chorizos.all { it.priceCents == 1050L },
        )

        // No label should still contain a price embedded in it (signature of the
        // "stuck label+price" bug, e.g. "1 SUPP ING 3E 10,50").
        val labelWithPrice = items.firstOrNull {
            Regex("""\d+[.,]\d{2}""").containsMatchIn(it.label)
        }
        assertEquals(
            "Label still contains an inline price: ${labelWithPrice?.label}",
            null,
            labelWithPrice,
        )
    }

    /**
     * Bug 02 — "Crêperie de la Poste". This ticket also has two price columns
     * (unit + total) BUT the unit column is sparse: it only prints when qty > 1
     * (2× CRE MARRON 5,50 and 4× CHOCOLAT 3,50). With the previous "always drop
     * the right column" rule we kept the 2-price column and lost 7 items out of
     * 8 (total parsed: 9 € instead of 56,30 €).
     *
     * The fix is the asymmetric-cluster heuristic in [dropDuplicatePriceColumns]:
     * when one cluster has < 50 % the size of the biggest, drop the small one
     * and keep the well-populated total column.
     *
     * Notes on this fixture:
     *  - OCR misread "2.10" as "2.40" for CAFE ALLONGE, so the parsed total is
     *    56,60 € (not 56,30 €). User can fix in the UI; not the parser's job.
     *  - OCR dropped the "x" between "1" and "FLAMB MARRON RHUM" for that one
     *    line, hence the QTY_BARE fallback regex.
     */
    @Test
    fun `bug-02 creperie — asymmetric price columns keep the populated one`() {
        val tokens = loadFixture("bug-02-creperie.log")
        val items = extractItems(tokens)

        // Sanity: we should now detect ~8 item lines.
        assertTrue(
            "Expected ≥ 7 items, got ${items.size}: ${items.map { "${it.label}=${it.priceCents}×${it.quantity}" }}",
            items.size >= 7,
        )

        // Sum of all detected item prices (price × qty since these are line totals).
        // 11.00 + 9.30 + 3.70 + 6.90 + 5.80 + 14.00 + 3.50 + 2.40(=ocr-misread of 2.10) = 56.60
        val total = items.sumOf { it.priceCents }
        assertEquals(
            "Sum of item line-totals should be 56,60 € (OCR misread 2.10 as 2.40)",
            5660L,
            total,
        )

        // Specific expectations on the two qty>1 lines.
        val cre = items.firstOrNull { it.label.contains("CRE MARRON", ignoreCase = true) }
        assertNotNull("Missing '2x CRE MARRON' line", cre)
        assertEquals(2, cre!!.quantity)
        assertEquals(1100L, cre.priceCents)

        val choc = items.firstOrNull { it.label.startsWith("CHOCOLAT", ignoreCase = true) }
        assertNotNull("Missing '4x CHOCOLAT' line", choc)
        assertEquals(4, choc!!.quantity)
        assertEquals(1400L, choc.priceCents)

        // The "1 FLAMB MARRON RHUM" line (OCR lost the 'x') should still be
        // detected as qty=1 with a clean label.
        val flamb = items.firstOrNull { it.label.contains("FLAMB", ignoreCase = true) }
        assertNotNull("Missing 'FLAMB MARRON RHUM' line", flamb)
        assertEquals(1, flamb!!.quantity)
        assertEquals(930L, flamb.priceCents)
        assertTrue(
            "FLAMB label should not start with a stray '1': '${flamb.label}'",
            !flamb.label.trimStart().startsWith("1"),
        )

        // No label should retain the trailing tax-code letter ("… D" or "… C").
        val withTaxCode = items.firstOrNull {
            Regex("""\s[A-Z]$""").containsMatchIn(it.label)
        }
        assertEquals(
            "Label should not keep the trailing tax-code letter: '${withTaxCode?.label}'",
            null,
            withTaxCode,
        )

        // The TOTAL row (56,30) must not appear as an item.
        val totalLeaked = items.firstOrNull { it.priceCents == 5630L }
        assertEquals("TOTAL line leaked as an item: ${totalLeaked?.label}", null, totalLeaked)
    }
}
