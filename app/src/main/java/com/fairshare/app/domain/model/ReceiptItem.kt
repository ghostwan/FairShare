package com.fairshare.app.domain.model

data class ReceiptItem(
    val id: String,
    val label: String,
    val amount: Double,
    val assignedParticipantIds: Set<String>
)
