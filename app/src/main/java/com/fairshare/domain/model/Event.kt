package com.fairshare.domain.model

data class Event(
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val currency: String = "EUR",
    val createdAt: Long = System.currentTimeMillis(),
)
