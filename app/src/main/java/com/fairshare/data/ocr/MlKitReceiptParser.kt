package com.fairshare.data.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fairshare.domain.model.ReceiptItem
import com.fairshare.domain.repository.ReceiptParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Parses receipts using ML Kit Text Recognition.
 *
 * Strategy: ML Kit groups text in [Block] → [Line] → [Element] (~word). On real receipts
 * the item label and the price are often split into different [Line]s because of the
 * large horizontal whitespace between them. We therefore work at the *element* level
 * and re-group elements into rows based on Y geometry, then sort by X.
 *
 *  1. Collect every element with its bounding box.
 *  2. Group elements into rows: two elements share a row if their vertical centers
 *     differ by less than ~60% of the median element height.
 *  3. For each row, the right-most element matching the price regex is the price;
 *     everything to its left is the label.
 *  4. Filter rows that look like totals / payment / tax (configurable noise patterns).
 *  5. Also de-duplicate consecutive identical rows (some OCR engines output them).
 */
class MlKitReceiptParser @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReceiptParser {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun parse(imageUri: Uri): List<ReceiptItem> {
        val image = InputImage.fromFilePath(context, imageUri)
        val visionText = suspendCancellableCoroutine<Text> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val tokens = visionText.textBlocks
            .asSequence()
            .flatMap { it.lines.asSequence() }
            .flatMap { it.elements.asSequence() }
            .mapNotNull { el ->
                val b = el.boundingBox ?: return@mapNotNull null
                Token(
                    cx = (b.left + b.right) / 2,
                    cy = (b.top + b.bottom) / 2,
                    height = b.height(),
                    text = el.text,
                )
            }
            .toList()
        dumpTokensForDebug(tokens)
        return extractItems(tokens)
    }

    /**
     * Dumps every OCR element so a real-world bug can be reproduced as a unit-test fixture.
     * View with:
     *     adb logcat -s ReceiptOCR:V
     * Each line: `text|cx|cy|height`
     * Disabled in release builds.
     */
    private fun dumpTokensForDebug(tokens: List<Token>) {
        if (!com.fairshare.BuildConfig.DEBUG) return
        Log.i(TAG, "=== BEGIN receipt dump (${tokens.size} tokens) ===")
        tokens.forEach { t ->
            Log.i(TAG, "${t.text.replace('\n', ' ')}|${t.cx}|${t.cy}|${t.height}")
        }
        Log.i(TAG, "=== END receipt dump ===")
    }

    companion object {
        private const val TAG = "ReceiptOCR"

        /** Price pattern: optional minus, 1–4 digits, decimal separator, exactly 2 decimals.
         *  Accepts optional currency symbol after the number. */
        private val PRICE_REGEX = Regex("""^-?\d{1,4}[.,]\d{2}(?:€|EUR|USD|\$|£)?$""")
        private val PRICE_ANY_REGEX = Regex("""-?\d{1,4}[.,]\d{2}""")

        /** Tokens used as the *label* prefix that indicate the row is NOT an item. */
        private val NOISE_PATTERNS = listOf(
            // Totals & subtotals
            Regex("""(?i)^s?\s*-?\s*total"""),
            Regex("""(?i)^sous[\s-]?total"""),
            Regex("""(?i)^subtotal"""),
            Regex("""(?i)\bttc\b"""),
            Regex("""(?i)\bht\b"""),
            Regex("""(?i)montant"""),
            Regex("""(?i)net\s+(à|a)\s+payer"""),
            Regex("""(?i)^a\s+payer"""),
            // Tax
            Regex("""(?i)^tva"""),
            Regex("""(?i)^tax"""),
            Regex("""(?i)^vat"""),
            // Payments
            Regex("""(?i)esp(è|e)ces"""),
            Regex("""(?i)^cb\b"""),
            Regex("""(?i)carte\s+bancaire"""),
            Regex("""(?i)^carte"""),
            Regex("""(?i)cheque|chèque"""),
            Regex("""(?i)monnaie"""),
            Regex("""(?i)^rendu"""),
            Regex("""(?i)^change"""),
            Regex("""(?i)paiement"""),
            Regex("""(?i)remise|discount"""),
            Regex("""(?i)pourboire|tip|service"""),
            Regex("""(?i)^par\s+couvert"""),
            Regex("""(?i)^couvert\b"""),
            // Misc receipt metadata
            Regex("""(?i)^tel|t(é|e)l(\.|:)"""),
            Regex("""(?i)siret|tva\s*intra"""),
            Regex("""(?i)ticket|n[°o]\s*\d"""),
            Regex("""(?i)caisse|cashier|serveur"""),
        )

        data class Token(val cx: Int, val cy: Int, val height: Int, val text: String)

        fun extractItems(tokens: List<Token>): List<ReceiptItem> {
            if (tokens.isEmpty()) return emptyList()

            val cleaned = dropDuplicatePriceColumns(splitStuckPriceTokens(tokens))

            val medianHeight = cleaned.map { it.height }.sorted()[cleaned.size / 2].coerceAtLeast(1)
            val rowTolerance = (medianHeight * 0.6).toInt().coerceAtLeast(6)

            // Group tokens by row using Y proximity from the row *anchor* (first token's
            // cy), not from the previously appended token. Single-link (last-token)
            // clustering breaks down on receipts whose right-most price column is
            // slightly mis-aligned: every right-col price bridges its left-col
            // neighbour to the next row, collapsing several lines into one.
            val sortedByY = cleaned.sortedBy { it.cy }
            val rows = mutableListOf<MutableList<Token>>()
            val anchors = mutableListOf<Int>()
            for (t in sortedByY) {
                val lastIdx = rows.lastIndex
                if (lastIdx >= 0 && abs(t.cy - anchors[lastIdx]) <= rowTolerance) {
                    rows[lastIdx] += t
                } else {
                    rows += mutableListOf(t)
                    anchors += t.cy
                }
            }

            return rows.flatMap { row -> rowToItems(row) }
        }

        /**
         * Some receipts (restaurants in particular) print two aligned price columns:
         * "EUR/U" (unit price) and "EUR" (line total). When qty=1 they are identical
         * but their cy is slightly offset, which breaks the row clustering: the
         * right-most price ends up anchoring a new row that swallows the *next*
         * item's leftmost tokens.
         *
         * Heuristic: cluster clean price tokens by cx, and if we detect ≥ 2 distinct
         * columns separated by a wide horizontal gap, keep only the column whose
         * cy values are best aligned with the rest of the page (i.e. the one whose
         * baseline is closest to the label rows). In practice that's the *left*
         * price column on French restaurant tickets ("EUR/U" — unit price — sits
         * closer to the label baseline than the "EUR" total column further right).
         */
        internal fun dropDuplicatePriceColumns(tokens: List<Token>): List<Token> {
            val priceTokens = tokens.filter { PRICE_REGEX.matches(it.text.trim()) }
            if (priceTokens.size < 4) return tokens
            val sortedByCx = priceTokens.sortedBy { it.cx }
            val medianHeight = tokens.map { it.height }.sorted()[tokens.size / 2]
            val threshold = medianHeight * 2
            // We want to detect a duplicate price column on the *right* side of the
            // receipt. Find the right-most gap wider than the threshold: tokens to
            // its right form the duplicate column we want to drop. Using the
            // right-most qualifying gap (rather than the absolute biggest one)
            // avoids being fooled by isolated stray prices on the left (e.g. the
            // "PAR COUVERT : 19,75" footer line).
            var splitIdx = -1
            for (i in 1 until sortedByCx.size) {
                val g = sortedByCx[i].cx - sortedByCx[i - 1].cx
                if (g >= threshold) splitIdx = i
            }
            if (splitIdx < 0) return tokens
            val rightColumn = sortedByCx.subList(splitIdx, sortedByCx.size).toSet()
            return tokens.filterNot { it in rightColumn }
        }

        /**
         * Splits tokens where OCR concatenated a label and a price without space
         * (e.g. "BRUT7,50" → ["BRUT", "7,50"]). The synthetic price token reuses
         * the source token's cy/height; cx is shifted to the right so cx ordering
         * stays correct.
         */
        internal fun splitStuckPriceTokens(tokens: List<Token>): List<Token> {
            val stuck = Regex("""^(.+?)(-?\d{1,4}[.,]\d{2})$""")
            val out = mutableListOf<Token>()
            for (t in tokens) {
                val m = stuck.matchEntire(t.text)
                val label = m?.groupValues?.get(1)
                if (m == null || label.isNullOrBlank() || label.last().isDigit()) {
                    out += t
                } else {
                    out += t.copy(text = label.trim())
                    out += t.copy(text = m.groupValues[2], cx = t.cx + t.height * 3)
                }
            }
            return out
        }

        private fun rowToItems(row: List<Token>): List<ReceiptItem> {
            val sorted = row.sortedBy { it.cx }
            // Identify the rightmost token whose text is a clean price.
            val priceIdx = sorted.indexOfLast { PRICE_REGEX.matches(it.text.trim()) }
            val (priceText, labelTokens) = if (priceIdx >= 0) {
                sorted[priceIdx].text to (sorted.subList(0, priceIdx) + sorted.subList(priceIdx + 1, sorted.size))
            } else {
                val last = sorted.lastOrNull() ?: return emptyList()
                val m = PRICE_ANY_REGEX.find(last.text) ?: return emptyList()
                m.value to (sorted.dropLast(1))
            }

            val price = priceText.trim()
                .replace(Regex("[€$£]|EUR|USD"), "")
                .replace(',', '.')
                .toDoubleOrNull() ?: return emptyList()
            if (price <= 0.0 || price > 9999.0) return emptyList()
            val totalCents = Math.round(price * 100)

            val rawLabel = labelTokens.joinToString(" ") { it.text }.trim()
            val (qty, cleanLabel) = extractQuantityAndLabel(rawLabel)

            val finalLabel = cleanLabel
                .trim()
                .trim('.', '-', ':', ' ', '\t', '*')

            if (finalLabel.length < 2) return emptyList()
            if (NOISE_PATTERNS.any { it.containsMatchIn(finalLabel) }) return emptyList()
            if (finalLabel.matches(Regex("""^[\d.,\s/%]+$"""))) return emptyList()

            return listOf(
                ReceiptItem(
                    id = UUID.randomUUID().toString(),
                    label = finalLabel.take(80),
                    priceCents = totalCents,
                    quantity = qty,
                )
            )
        }

        private val QTY_LEADING = Regex("""^\s*(\d{1,2})\s*[xX*]\s+(.+)$""")
        private val QTY_TRAILING = Regex("""^(.+?)\s+[xX*]\s*(\d{1,2})\s*$""")
        private val QTY_STUCK = Regex("""^(\d{1,2})[xX*]\s*(.+)$""")

        /** Returns (quantity, cleanLabel). quantity defaults to 1 if no marker is found. */
        internal fun extractQuantityAndLabel(label: String): Pair<Int, String> {
            QTY_LEADING.matchEntire(label)?.let { m ->
                val q = m.groupValues[1].toIntOrNull() ?: 1
                return (q.takeIf { it in 1..50 } ?: 1) to m.groupValues[2]
            }
            QTY_STUCK.matchEntire(label)?.let { m ->
                val q = m.groupValues[1].toIntOrNull() ?: 1
                return (q.takeIf { it in 1..50 } ?: 1) to m.groupValues[2]
            }
            QTY_TRAILING.matchEntire(label)?.let { m ->
                val q = m.groupValues[2].toIntOrNull() ?: 1
                return (q.takeIf { it in 1..50 } ?: 1) to m.groupValues[1]
            }
            return 1 to label
        }
    }
}
