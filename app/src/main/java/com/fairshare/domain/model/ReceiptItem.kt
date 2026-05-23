package com.fairshare.domain.model

/** A scanned line item from a receipt. */
data class ReceiptItem(
    val id: String,
    val label: String,
    val priceCents: Long,
    /** Participant ids assigned to this item (split equally between them). */
    val assignedTo: Set<Long> = emptySet(),
)
