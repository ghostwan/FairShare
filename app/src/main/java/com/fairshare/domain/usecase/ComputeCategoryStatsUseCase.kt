package com.fairshare.domain.usecase

import com.fairshare.domain.model.Category
import com.fairshare.domain.model.DefaultCategories
import com.fairshare.domain.model.Expense

/**
 * One aggregated row in the Stats tab.
 *
 * @property category resolved [Category] (default or custom) — or `null`
 *   for the synthetic "Uncategorized" bucket.
 * @property totalCents sum of [Expense.amountCents] of all expenses in
 *   this bucket. Settlements are excluded by the use case (they net to
 *   zero across participants and would skew the picture).
 * @property count number of contributing expenses.
 * @property fraction share of [totalCents] in the grand total, in
 *   `[0.0, 1.0]`. Pre-computed here so the UI doesn't have to.
 */
data class CategoryStat(
    val category: Category?,
    val totalCents: Long,
    val count: Int,
    val fraction: Double,
)

/**
 * Aggregates the expenses of an event by category id for the Stats tab.
 *
 * Resolution rules:
 *
 * - Settlement expenses (`isSettlement == true`) are dropped: they
 *   represent a transfer between two participants, not a real spend,
 *   and would inflate one bucket while skewing the total.
 * - `categoryId == null` lands in a synthetic "Uncategorized" bucket
 *   (rendered with [category] = `null`) so the user can spot expenses
 *   that still need tagging.
 * - Unknown ids (eg. a custom category that was deleted on another
 *   device but the expense still references) are merged into the
 *   "Uncategorized" bucket — same render path, no orphan rows.
 *
 * Output is sorted by [CategoryStat.totalCents] descending so the
 * biggest expense category is always at the top of the screen.
 */
class ComputeCategoryStatsUseCase {

    operator fun invoke(
        expenses: List<Expense>,
        customCategories: List<Category>,
    ): List<CategoryStat> {
        val relevant = expenses.filterNot { it.isSettlement }
        if (relevant.isEmpty()) return emptyList()

        val customById = customCategories.associateBy { it.id }
        fun resolve(id: String?): Category? =
            if (id == null) null else DefaultCategories.BY_ID[id] ?: customById[id]

        val total = relevant.sumOf { it.amountCents }.coerceAtLeast(1L)

        return relevant
            .groupBy { resolve(it.categoryId) }
            .map { (category, group) ->
                val sum = group.sumOf { it.amountCents }
                CategoryStat(
                    category = category,
                    totalCents = sum,
                    count = group.size,
                    fraction = sum.toDouble() / total.toDouble(),
                )
            }
            .sortedByDescending { it.totalCents }
    }
}
