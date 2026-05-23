package com.fairshare.app.domain.model

data class Receipt(
    val id: String,
    val merchant: String,
    val payerId: String,
    val items: List<ReceiptItem>
)
