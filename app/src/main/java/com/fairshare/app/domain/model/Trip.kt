package com.fairshare.app.domain.model

data class Trip(
    val id: String,
    val title: String,
    val participants: List<Participant>,
    val expenses: List<Expense>,
    val receipt: Receipt?
)
