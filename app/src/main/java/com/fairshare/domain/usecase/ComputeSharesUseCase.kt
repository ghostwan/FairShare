package com.fairshare.domain.usecase

import com.fairshare.domain.model.ExpenseShare
import com.fairshare.domain.model.SplitMode

/**
 * Computes individual shares (in cents) from a total amount and a split mode.
 * Guarantees that the sum of returned shares equals [amountCents] (rounding correction).
 */
class ComputeSharesUseCase {

    operator fun invoke(
        amountCents: Long,
        participantIds: List<String>,
        mode: SplitMode,
        // ratios used for SHARES (e.g. [1,1,2]); exact amounts (cents) for EXACT
        weights: List<Long> = emptyList(),
    ): List<ExpenseShare> {
        require(participantIds.isNotEmpty()) { "Need at least one participant" }
        return when (mode) {
            SplitMode.EQUAL -> splitEqual(amountCents, participantIds)
            SplitMode.SHARES -> splitByWeights(amountCents, participantIds, weights)
            SplitMode.EXACT -> participantIds.mapIndexed { i, id ->
                ExpenseShare(id, weights.getOrElse(i) { 0L })
            }
        }
    }

    private fun splitEqual(total: Long, ids: List<String>): List<ExpenseShare> {
        val base = total / ids.size
        val remainder = (total - base * ids.size).toInt()
        return ids.mapIndexed { i, id ->
            ExpenseShare(id, base + if (i < remainder) 1 else 0)
        }
    }

    private fun splitByWeights(total: Long, ids: List<String>, weights: List<Long>): List<ExpenseShare> {
        require(weights.size == ids.size) { "Weights must match participants" }
        val sum = weights.sum().coerceAtLeast(1)
        var distributed = 0L
        val shares = ids.mapIndexed { i, id ->
            val v = if (i == ids.lastIndex) total - distributed
            else (total * weights[i] / sum).also { distributed += it }
            ExpenseShare(id, v)
        }
        return shares
    }
}
