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
            Regex("""(?i)ticket|\bn[°o]\s*\d"""),
            Regex("""(?i)caisse|cashier|serveur"""),
        )

        data class Token(val cx: Int, val cy: Int, val height: Int, val text: String)

        fun extractItems(tokens: List<Token>): List<ReceiptItem> {
            if (tokens.isEmpty()) return emptyList()

            val cleaned = dropDuplicatePriceColumns(splitStuckPriceTokens(tokens))

            val medianHeight = cleaned.map { it.height }.sorted()[cleaned.size / 2].coerceAtLeast(1)
            val rowTolerance = (medianHeight * 0.6).toInt().coerceAtLeast(6)

            // Photos taken at an angle systematically push the right-side price
            // column N pixels higher than the left-side label column (perspective
            // tilt). Without correction the anchor-walk pairs every label with
            // the *next* row's price. [shiftPricesForTilt] detects the best
            // vertical offset to add to every price token so that they realign
            // with the label rows, then we proceed with the regular clustering.
            val tiltCorrected = shiftPricesForTilt(cleaned, medianHeight, rowTolerance)

            // Group tokens by row using Y proximity from the row *anchor* (first token's
            // cy), not from the previously appended token. Single-link (last-token)
            // clustering breaks down on receipts whose right-most price column is
            // slightly mis-aligned: every right-col price bridges its left-col
            // neighbour to the next row, collapsing several lines into one.
            val sortedByY = tiltCorrected.sortedBy { it.cy }
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

            // Multi-line item reassembly (bug-04): receipts that print one item
            // across 3 visual lines (label part 1 / label part 2 + price / SKU)
            // produce 3 separate rows here because intra-item gaps (~90 px) exceed
            // [rowTolerance]. Merge label-only rows above each price row into it,
            // and drop SKU-like rows below.
            val merged = mergeMultiLineLabels(rows, anchors, medianHeight)

            return merged.flatMap { row -> rowToItems(row) }
        }

        /**
         * Reassembles items whose label is broken across several OCR rows.
         *
         * Walks the row list and, for each row that contains a [PRICE_REGEX]
         * token, performs two extensions within the *intra-item gap* (≈
         * `medianHeight × 1.5` — wide enough to bridge ~90 px line spacings of
         * bug-04, tight enough to never bridge the ~155 px inter-item gaps of
         * the same ticket):
         *
         *  1. **Up**: consume consecutive label-only rows immediately above,
         *     prepending their tokens to the price row. Stops when no row
         *     above, the row above already has a price, or the cy gap exceeds
         *     the threshold.
         *  2. **Down**: consume consecutive label-only rows immediately below
         *     *only when they look like a SKU code* (one alphanumeric token,
         *     length ≥ 6, mixing letters and digits, no whitespace). Their
         *     tokens are discarded — codes like `CHAUSSE22POIZ` add noise to
         *     the label and convey no useful info to the user.
         *
         * Rows on single-line receipts (bug-01/02/03) already carry their
         * price on the same row as the label, so neither extension fires and
         * the output is identical to the input.
         */
        internal fun mergeMultiLineLabels(
            rows: List<MutableList<Token>>,
            anchors: List<Int>,
            medianHeight: Int,
        ): List<List<Token>> {
            if (rows.size <= 1) return rows
            // 1.3× rather than 1.5× because real receipts have a clear gap
            // between intra-item lines (~ 1.1×) and inter-item lines (~ 2×),
            // while synthetic test fixtures use exactly 1.5× spacing and would
            // collapse otherwise. 1.3× still comfortably absorbs bug-04's
            // 1.1× intra-item gaps without bridging the 2× inter-item ones.
            val intraGap = (medianHeight * 1.3).toInt().coerceAtLeast(1)

            fun hasPrice(row: List<Token>): Boolean =
                row.any { PRICE_REGEX.matches(it.text.trim()) }

            // True if the row carries enough "text" to plausibly be a label
            // continuation: at least one token of length ≥ 2 containing a letter.
            // Filters out rows whose only non-price content is a stray "€" or
            // an OCR-misread "e" — those are NOT real label fragments and
            // shouldn't pull the up-walk over an item boundary (bug-03).
            fun hasRealLabel(row: List<Token>): Boolean = row.any { t ->
                val txt = t.text.trim()
                txt.length >= 2 && txt.any { it.isLetter() }
            }

            fun isSkuLike(row: List<Token>): Boolean {
                // Strip currency residues first — receipts often print the SKU
                // with a duplicated price+€ inline that ends up as 2 tokens
                // here ([CHAUSSE22POIZ, €]); the SKU itself is the only
                // "real" token left.
                val meaningful = row.filterNot { it.text.trim() in CURRENCY_SYMBOLS }
                if (meaningful.size != 1) return false
                val txt = meaningful[0].text.trim()
                if (txt.length < 6) return false
                if (!txt.all { it.isLetterOrDigit() }) return false
                val hasLetter = txt.any { it.isLetter() }
                val hasDigit = txt.any { it.isDigit() }
                if (!(hasLetter && hasDigit)) return false
                return txt.count { it.isDigit() } >= 2
            }

            val consumed = BooleanArray(rows.size)
            val out = mutableListOf<List<Token>>()

            for (i in rows.indices) {
                if (consumed[i]) continue
                val row = rows[i]
                if (!hasPrice(row)) {
                    out += row
                    continue
                }

                val accumulated = row.toMutableList()

                // 1. Up-walk: pull in label-only rows above (must carry real text).
                var j = i - 1
                var prevAnchor = anchors[i]
                while (j >= 0 && !consumed[j] && !hasPrice(rows[j])) {
                    if (prevAnchor - anchors[j] > intraGap) break
                    if (!hasRealLabel(rows[j])) break
                    accumulated += rows[j]
                    consumed[j] = true
                    prevAnchor = anchors[j]
                    j--
                }

                // 2. Down-walk: drop SKU-like rows below.
                var k = i + 1
                var lastAnchor = anchors[i]
                while (k < rows.size && !consumed[k] && !hasPrice(rows[k])) {
                    if (anchors[k] - lastAnchor > intraGap) break
                    if (!isSkuLike(rows[k])) break
                    consumed[k] = true
                    lastAnchor = anchors[k]
                    k++
                }

                out += accumulated
                consumed[i] = true
            }

            return out
        }

        /**
         * Detects perspective-tilt: when a receipt photo is taken at an angle,
         * the right-side price column ends up systematically N pixels higher
         * than the left-side label column. The anchor-walk row clustering then
         * pairs every label with the *next* row's price — every item is wrong.
         *
         * Strategy:
         *  1. Cluster non-price label tokens into rows by Y proximity, compute
         *     each row's centroid cy.
         *  2. For each candidate δ ∈ [0, medianHeight × 1.5], compute the sum
         *     over all prices of `min(|price.cy + δ − centroid|)`, clamped at
         *     `rowTolerance × 2` (a price farther than 2 rows away contributes
         *     a flat penalty rather than dominating the sum).
         *  3. Pick the δ that *minimises* this distance sum. Ties go to the
         *     smaller δ.
         *
         * Returns the original tokens with each price token's cy shifted by
         * the chosen δ. δ=0 returns tokens unchanged (no shift applied).
         */
        internal fun shiftPricesForTilt(
            tokens: List<Token>,
            medianHeight: Int,
            rowTolerance: Int,
        ): List<Token> {
            val prices = tokens.filter { PRICE_REGEX.matches(it.text.trim()) }
            if (prices.size < 3) return tokens

            // 1. Cluster label tokens into rows by Y proximity, keep real
            //    labels. Excludes SKU-like rows (single alphanumeric token,
            //    length ≥ 6, mixing letters and digits with ≥ 2 digits) — those
            //    appear *below* the price on multi-line receipts (bug-04) and
            //    would otherwise lure the shift onto the SKU row.
            val labelTokens = tokens
                .filter { t ->
                    val txt = t.text.trim()
                    !PRICE_REGEX.matches(txt) &&
                        txt !in CURRENCY_SYMBOLS &&
                        txt.length >= 2 &&
                        txt.any { c -> c.isLetter() }
                }
                .sortedBy { it.cy }
            if (labelTokens.isEmpty()) return tokens

            fun isSkuRow(row: List<Token>): Boolean {
                if (row.size != 1) return false
                val txt = row[0].text.trim()
                if (txt.length < 6) return false
                if (!txt.all { it.isLetterOrDigit() }) return false
                val hasLetter = txt.any { it.isLetter() }
                val hasDigit = txt.any { it.isDigit() }
                return hasLetter && hasDigit && txt.count { it.isDigit() } >= 2
            }

            val labelRowCentroids = mutableListOf<Int>()
            val pendingRow = mutableListOf<Token>()
            var anchor = Int.MIN_VALUE
            fun flush() {
                if (pendingRow.isNotEmpty() && !isSkuRow(pendingRow)) {
                    labelRowCentroids += pendingRow.map { it.cy }.average().toInt()
                }
                pendingRow.clear()
            }
            for (t in labelTokens) {
                if (anchor == Int.MIN_VALUE || abs(t.cy - anchor) > rowTolerance) {
                    flush()
                    anchor = t.cy
                }
                pendingRow += t
            }
            flush()
            if (labelRowCentroids.isEmpty()) return tokens

            // 2. Distance-sum scoring over candidate δ values.
            val maxDelta = (medianHeight * 1.5).toInt()
            val step = (medianHeight / 10).coerceAtLeast(1)
            val penaltyCap = rowTolerance * 2
            var bestDelta = 0
            var bestCost = Long.MAX_VALUE
            var delta = 0
            while (delta <= maxDelta) {
                var cost = 0L
                for (p in prices) {
                    val shifted = p.cy + delta
                    var nearest = Int.MAX_VALUE
                    for (c in labelRowCentroids) {
                        val d = abs(shifted - c)
                        if (d < nearest) nearest = d
                    }
                    cost += nearest.coerceAtMost(penaltyCap).toLong()
                }
                if (cost < bestCost) {
                    bestCost = cost
                    bestDelta = delta
                }
                delta += step
            }
            // Only apply when the shift is large enough to actually move
            // prices into a different row; otherwise the clustering's existing
            // tolerance already absorbs the jitter and we shouldn't disturb
            // perfectly-aligned receipts (bug-01/02/03/04).
            if (bestDelta < rowTolerance) return tokens
            val shift = bestDelta
            return tokens.map { t ->
                if (PRICE_REGEX.matches(t.text.trim())) t.copy(cy = t.cy + shift) else t
            }
        }

        /**
         * Some receipts print two aligned price columns. We've observed three
         * very different shapes in the wild:
         *
         *  - **Symmetric** (bug-01, "La Perrozienne"): both `EUR/U` (unit price)
         *    and `EUR` (line total) print on *every* item line. Two full columns
         *    of equal length, each row aligned with its label. We must drop one
         *    of the two columns — the left one is the safe choice (better label
         *    alignment, and [ExpandReceiptQuantitiesUseCase] re-multiplies
         *    later).
         *
         *  - **Asymmetric** (bug-02, "Crêperie de la Poste"): the unit-price
         *    column only prints for lines with qty > 1, so it is sparse (2–3
         *    prices). The right column carries every line total. Here we MUST
         *    keep the right (bigger) column, otherwise we lose every qty=1
         *    item.
         *
         *  - **Multi-line** (bug-03, "Côte Rivière"): one column lives on the
         *    *same* row as the label (line total on the right) while the other
         *    column lives on a *separate* row below the label (unit price under
         *    the label). If we kept the orphan column, every row would lose
         *    its price; we MUST keep the column that aligns with the labels.
         *
         * Heuristic (applied in order):
         *  1. Cluster clean price tokens by cx using a "wide gap" splitter
         *     (gap ≥ 2 × median token height starts a new cluster).
         *  2. Drop outlier singleton clusters (size ≤ 2 and < 25 % of the
         *     biggest cluster). These are stray prices like a lone
         *     "PAR COUVERT : 19,75" footer.
         *  3. **Label alignment**: for each remaining cluster, compute the
         *     fraction of its prices whose cy is within the row tolerance of
         *     a non-price token's cy. If clusters differ sharply (best –
         *     worst ≥ 0.5) keep the best-aligned cluster. This covers bug-03.
         *  4. Otherwise (all clusters align with labels) → size-based:
         *     - symmetric (ratio ≥ 0.5) → keep LEFT-most cluster (bug-01)
         *     - asymmetric → keep BIGGEST cluster (bug-02)
         */
        internal fun dropDuplicatePriceColumns(tokens: List<Token>): List<Token> {
            val priceTokens = tokens.filter { PRICE_REGEX.matches(it.text.trim()) }
            if (priceTokens.size < 4) return tokens
            val sortedByCx = priceTokens.sortedBy { it.cx }
            val medianHeight = tokens.map { it.height }.sorted()[tokens.size / 2]
            val threshold = medianHeight * 2

            // 1. Partition prices into cx-clusters separated by wide gaps.
            val clusters = mutableListOf<MutableList<Token>>()
            clusters += mutableListOf(sortedByCx.first())
            for (i in 1 until sortedByCx.size) {
                val gap = sortedByCx[i].cx - sortedByCx[i - 1].cx
                if (gap >= threshold) clusters += mutableListOf(sortedByCx[i])
                else clusters.last() += sortedByCx[i]
            }
            if (clusters.size < 2) return tokens

            // 2. Drop outlier singleton clusters (lone footer prices, …).
            val biggestRaw = clusters.maxOf { it.size }
            val outlierCutoff = (biggestRaw * 0.25).coerceAtLeast(1.0)
            val outlierTokens: Set<Token> = clusters
                .filter { it.size <= 2 && it.size < outlierCutoff }
                .flatten()
                .toSet()
            val significant = clusters.filter { c -> c.none { it in outlierTokens } }
            if (significant.size < 2) {
                return tokens.filterNot { it in outlierTokens }
            }

            // 3. Label-alignment score: prefer the cluster whose prices live on
            //    the same rows as actual labels. This catches receipts where a
            //    column sits on its own (orphan) rows whose only neighbour is
            //    a "€" symbol token (bug-03).
            val rowTolerance = (medianHeight * 0.6).toInt().coerceAtLeast(6)
            // Build label-row cy anchors from non-price tokens. A row counts as
            // a "label row" only if it contains at least one token of length ≥ 2
            // with at least one letter (filters out lone "€" / "*" / digits).
            val nonPriceSorted = tokens
                .filterNot { PRICE_REGEX.matches(it.text.trim()) }
                .sortedBy { it.cy }
            val labelRowCys = mutableListOf<Int>()
            var anchor = Int.MIN_VALUE
            val pendingRow = mutableListOf<Token>()
            fun commit() {
                val isLabel = pendingRow.any { t ->
                    val txt = t.text.trim()
                    txt.length >= 2 && txt.any { it.isLetter() }
                }
                if (isLabel) labelRowCys += anchor
                pendingRow.clear()
            }
            for (t in nonPriceSorted) {
                if (anchor == Int.MIN_VALUE || abs(t.cy - anchor) > rowTolerance) {
                    commit()
                    anchor = t.cy
                }
                pendingRow += t
            }
            commit()

            fun alignmentRatio(cluster: List<Token>): Double {
                if (cluster.isEmpty()) return 0.0
                val aligned = cluster.count { p ->
                    labelRowCys.any { abs(p.cy - it) <= rowTolerance }
                }
                return aligned.toDouble() / cluster.size
            }
            val ratios = significant.map { alignmentRatio(it) }
            val bestRatio = ratios.max()
            val worstRatio = ratios.min()
            if (bestRatio - worstRatio >= 0.5) {
                val bestIdx = ratios.indexOfFirst { it == bestRatio }
                val dropped: Set<Token> = significant
                    .filterIndexed { i, _ -> i != bestIdx }
                    .flatten()
                    .toSet() + outlierTokens
                return tokens.filterNot { it in dropped }
            }

            // 4. Size-based decision among label-aligned clusters.
            val biggest = significant.maxOf { it.size }
            val smallest = significant.minOf { it.size }
            val symmetric = smallest * 2 >= biggest
            val kept: List<Token> = if (symmetric) {
                significant.first()                       // left-most
            } else {
                significant.first { it.size == biggest }  // biggest (left-most on tie)
            }

            val dropped: Set<Token> = significant
                .filter { it !== kept }
                .flatten()
                .toSet() + outlierTokens
            return tokens.filterNot { it in dropped }
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

            // Sort labels by visual reading order: bucket cys into bands of
            // medianHeight × 0.6 (≈ single-line tolerance) so tokens that
            // belong to the same visual line all share a band and sort by cx
            // within. Multi-line labels reassembled by [mergeMultiLineLabels]
            // fall into successive bands and stay in top-to-bottom order.
            // A plain (cy, cx) sort would scramble single-line rows whose
            // tokens have ~5 px cy jitter (bug-02: tax-code letter "D" at
            // cy 827 vs label tokens at cy 831 would move "D" to the front
            // and break QTY_LEADING regex).
            val labelOrdered = if (labelTokens.isEmpty()) {
                emptyList()
            } else {
                val filtered = labelTokens.filterNot { it.text.trim() in CURRENCY_SYMBOLS }
                if (filtered.isEmpty()) emptyList()
                else {
                    val maxH = filtered.maxOf { it.height }
                    val band = (maxH * 0.6).toInt().coerceAtLeast(6)
                    val minCy = filtered.minOf { it.cy }
                    filtered.sortedWith(compareBy({ (it.cy - minCy) / band }, { it.cx }))
                }
            }
            val rawLabel = labelOrdered.joinToString(" ") { it.text }.trim()
            val (qty, cleanLabel) = extractQuantityAndLabel(rawLabel)

            val finalLabel = cleanLabel
                .trim()
                .trim('.', '-', ':', ' ', '\t', '*')
                // Strip a trailing single-letter tax code ("… 9.30 D", "… 14.00 C")
                // that French restaurants append after the price.
                .replace(Regex("""\s+[A-Z]$"""), "")
                .trim()

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

        private val CURRENCY_SYMBOLS = setOf("€", "$", "£", "EUR", "USD")

        private val QTY_LEADING = Regex("""^\s*(\d{1,2})\s*[xX*]\s+(.+)$""")
        private val QTY_TRAILING = Regex("""^(.+?)\s+[xX*]\s*(\d{1,2})\s*$""")
        private val QTY_STUCK = Regex("""^(\d{1,2})[xX*]\s*(.+)$""")
        /** Fallback: "N LABEL" with no `x` separator (some OCR runs drop the `x`).
         *  Requires the label to start with a letter so we don't eat "1664 BIERE". */
        private val QTY_BARE = Regex("""^(\d{1,2})\s+([A-Za-zÀ-ÿ].{1,}.*)$""")

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
            QTY_BARE.matchEntire(label)?.let { m ->
                val q = m.groupValues[1].toIntOrNull() ?: 1
                return (q.takeIf { it in 1..50 } ?: 1) to m.groupValues[2]
            }
            return 1 to label
        }
    }
}
