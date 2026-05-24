package com.fairshare.domain.model

data class Event(
    val id: String = "",
    val name: String,
    val description: String? = null,
    val currency: String = "EUR",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * When `true`, the event is hidden from the main events list and
     * surfaced in the archive screen instead. Synchronized across
     * devices via the standard LWW snapshot path (see EventSnapshot).
     */
    val archived: Boolean = false,
)
