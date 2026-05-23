package com.fairshare.app.presentation

import com.fairshare.app.domain.model.Balance
import com.fairshare.app.domain.model.Settlement
import com.fairshare.app.domain.model.Trip

data class FairShareUiState(
    val trips: List<Trip>,
    val selectedTripId: String?,
    val trip: Trip?,
    val balances: List<Balance>,
    val settlements: List<Settlement>,
    val scanError: String? = null
)
