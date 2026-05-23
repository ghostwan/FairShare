package com.fairshare.domain.usecase

import com.fairshare.domain.model.ExpenseShare
import com.fairshare.domain.model.ReceiptItem

/**
 * Builds final shares from receipt items where each item is assigned to a subset of participants.
 * Each item's cost is split equally between the people who consumed it.
 * Items left unassigned are split equally between all [allParticipantIds] (fallback).
 */
class AssignReceiptItemsUseCase {

    operator fun invoke(
        items: List<ReceiptItem>,
        allParticipantIds: List<Long>,
    ): List<ExpenseShare> {
        val totals = mutableMapOf<Long, Long>().withDefault { 0L }
        items.forEach { item ->
            val assignees = item.assignedTo.toList().ifEmpty { allParticipantIds }
            if (assignees.isEmpty()) return@forEach
            val base = item.priceCents / assignees.size
            val remainder = (item.priceCents - base * assignees.size).toInt()
            assignees.forEachIndexed { i, pid ->
                val add = base + if (i < remainder) 1 else 0
                totals[pid] = totals.getValue(pid) + add
            }
        }
        return totals.map { (pid, amount) -> ExpenseShare(pid, amount) }
    }
}
