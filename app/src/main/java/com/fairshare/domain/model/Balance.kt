package com.fairshare.domain.model

/** Net balance per participant in cents. Positive = is owed; negative = owes. */
data class Balance(
    val participantId: Long,
    val participantName: String,
    val netCents: Long,
)

/** A transfer that, once paid, helps settling debts. */
data class Settlement(
    val fromId: Long,
    val fromName: String,
    val toId: Long,
    val toName: String,
    val amountCents: Long,
)
