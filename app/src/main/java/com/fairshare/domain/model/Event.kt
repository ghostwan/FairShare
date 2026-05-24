package com.fairshare.domain.model

data class Event(
    val id: String = "",
    val name: String,
    val description: String? = null,
    val currency: String = "EUR",
    val createdAt: Long = System.currentTimeMillis(),
)
