package com.fairshare.data.ocr

import android.content.Context
import android.net.Uri
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
        return extractItems(tokens)
    }

    companion object {
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
            // Misc receipt metadata
            Regex("""(?i)^tel|t(é|e)l(\.|:)"""),
            Regex("""(?i)siret|tva\s*intra"""),
            Regex("""(?i)ticket|n[°o]\s*\d"""),
            Regex("""(?i)caisse|cashier|serveur"""),
        )

        data class Token(val cx: Int, val cy: Int, val height: Int, val text: String)

        fun extractItems(tokens: List<Token>): List<ReceiptItem> {
            if (tokens.isEmpty()) return emptyList()

            val medianHeight = tokens.map { it.height }.sorted()[tokens.size / 2].coerceAtLeast(1)
            val rowTolerance = (medianHeight * 0.6).toInt().coerceAtLeast(6)

            // Group tokens by row using Y proximity.
            val sortedByY = tokens.sortedBy { it.cy }
            val rows = mutableListOf<MutableList<Token>>()
            for (t in sortedByY) {
                val last = rows.lastOrNull()
                if (last != null && abs(t.cy - last.last().cy) <= rowTolerance) {
                    last += t
                } else {
                    rows += mutableListOf(t)
                }
            }

            return rows.flatMap { row -> rowToItems(row) }
                // Same item appearing twice exactly is most likely a duplicate detection;
                // we de-duplicate only if more than one row produced the same (label, price)
                // pair — true quantity expansions yield several rows with the same label/price
                // which we *do* want to keep. So we don't dedupe here anymore.
        }

        private fun rowToItems(row: List<Token>): List<ReceiptItem> {
            val sorted = row.sortedBy { it.cx }
            // Identify the rightmost token whose text is a clean price.
            val priceIdx = sorted.indexOfLast { PRICE_REGEX.matches(it.text.trim()) }
            val (priceText, labelTokens) = if (priceIdx >= 0) {
                sorted[priceIdx].text to (sorted.subList(0, priceIdx) + sorted.subList(priceIdx + 1, sorted.size))
            } else {
                // Fallback: maybe price is fused to other characters (e.g. "12,90€" already a clean token,
                // or "x12.50"). Try matching anywhere on the rightmost token.
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

            // Detect a quantity prefix like "2x", "3 x", "2X", "x2", "2*" and split into N items.
            val (qty, cleanLabel) = extractQuantityAndLabel(rawLabel)

            val finalLabel = cleanLabel
                .trim()
                .trim('.', '-', ':', ' ', '\t', '*')

            if (finalLabel.length < 2) return emptyList()
            if (NOISE_PATTERNS.any { it.containsMatchIn(finalLabel) }) return emptyList()
            if (finalLabel.matches(Regex("""^[\d.,\s/%]+$"""))) return emptyList()

            // When qty > 1 we split the total evenly with rounding correction so the sum still
            // matches the printed amount. Each item keeps the same label so the user immediately
            // sees "Bière / 5,50" repeated N times and can assign each one to a different person.
            if (qty <= 1) {
                return listOf(
                    ReceiptItem(
                        id = UUID.randomUUID().toString(),
                        label = finalLabel.take(80),
                        priceCents = totalCents,
                    )
                )
            }
            val base = totalCents / qty
            val remainder = (totalCents - base * qty).toInt()
            return (0 until qty).map { i ->
                ReceiptItem(
                    id = UUID.randomUUID().toString(),
                    label = finalLabel.take(80),
                    priceCents = base + if (i < remainder) 1 else 0,
                )
            }
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
