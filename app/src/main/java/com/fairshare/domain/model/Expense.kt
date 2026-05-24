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
    val id: String = "",
    val eventId: String,
    val title: String,
    val amountCents: Long,
    val payerId: String,
    val date: Long = System.currentTimeMillis(),
    val shares: List<ExpenseShare> = emptyList(),
    val items: List<ExpenseItem> = emptyList(),
    /**
     * `true` when this row represents a reimbursement between two
     * participants (payer = debtor, single share on the creditor).
     * Same balance semantics as a regular expense; the flag only
     * drives a distinct rendering in the timeline and tags the row
     * as system-generated for history purposes.
     */
    val isSettlement: Boolean = false,
)

data class ExpenseShare(
    val participantId: String,
    val amountCents: Long,
)

/** Per-article detail of a scanned expense. */
data class ExpenseItem(
    val id: String = "",
    val label: String,
    val priceCents: Long,
    val quantity: Int = 1,
    val assignedTo: Set<String> = emptySet(),
)

enum class SplitMode { EQUAL, SHARES, EXACT }
