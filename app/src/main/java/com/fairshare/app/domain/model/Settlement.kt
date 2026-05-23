package com.fairshare.app.domain.model

data class Settlement(
    val from: Participant,
    val to: Participant,
    val amount: Double
)
