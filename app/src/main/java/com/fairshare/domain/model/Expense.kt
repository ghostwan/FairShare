package com.fairshare.domain.model

/**
 * An expense paid by a single participant, split between several participants.
 * Each share represents how much a participant owes for this expense.
 */
data class Expense(
    val id: Long = 0,
    val eventId: Long,
    val title: String,
    val amountCents: Long,
    val payerId: Long,
    val date: Long = System.currentTimeMillis(),
    val shares: List<ExpenseShare> = emptyList(),
)

data class ExpenseShare(
    val participantId: Long,
    val amountCents: Long,
)

enum class SplitMode { EQUAL, SHARES, EXACT }
