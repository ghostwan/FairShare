package com.fairshare.app.domain.model

data class Expense(
    val id: String,
    val label: String,
    val amount: Double,
    val payerId: String,
    val participantIds: Set<String>
)
