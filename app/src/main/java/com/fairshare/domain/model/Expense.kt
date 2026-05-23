package com.fairshare.domain.model

/**
 * An expense paid by a single participant, split between several participants.
 * Each share represents how much a participant owes for this expense.
 *
 * When the expense was created by scanning a receipt, [items] holds the per-article
 * detail so the user can re-edit each line and reassign it later. When [items] is
 * empty, the expense is a plain "total + equal split" entry.
 */
data class Expense(
    val id: Long = 0,
    val eventId: Long,
    val title: String,
    val amountCents: Long,
    val payerId: Long,
    val date: Long = System.currentTimeMillis(),
    val shares: List<ExpenseShare> = emptyList(),
    val items: List<ExpenseItem> = emptyList(),
)

data class ExpenseShare(
    val participantId: Long,
    val amountCents: Long,
)

/** Per-article detail of a scanned expense. */
data class ExpenseItem(
    val id: Long = 0,
    val label: String,
    val priceCents: Long,
    val quantity: Int = 1,
    val assignedTo: Set<Long> = emptySet(),
)

enum class SplitMode { EQUAL, SHARES, EXACT }
