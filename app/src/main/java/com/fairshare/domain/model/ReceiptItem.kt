package com.fairshare.domain.model

/** A scanned line item from a receipt. */
data class ReceiptItem(
    val id: String,
    val label: String,
    /** Total price for this row in cents. If [quantity] > 1, this is the row total. */
    val priceCents: Long,
    /** Number of units detected on the row (e.g. "2 x Bière" → 2). Default 1. */
    val quantity: Int = 1,
    /** Participant ids assigned to this item (split equally between them). */
    val assignedTo: Set<Long> = emptySet(),
)
