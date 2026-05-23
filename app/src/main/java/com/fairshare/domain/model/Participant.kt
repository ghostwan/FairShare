package com.fairshare.domain.model

data class Participant(
    val id: Long = 0,
    val eventId: Long,
    val name: String,
)
