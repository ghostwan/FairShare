package com.fairshare.app.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fairshare.app.domain.CalculateBalancesUseCase
import com.fairshare.app.domain.CalculateSettlementsUseCase
import com.fairshare.app.domain.ParseReceiptTextUseCase
import com.fairshare.app.domain.TripRepository

class FairShareViewModel(
    private val repository: TripRepository,
    private val calculateBalances: CalculateBalancesUseCase,
    private val calculateSettlements: CalculateSettlementsUseCase,
    private val parseReceiptText: ParseReceiptTextUseCase
) {
    var uiState by mutableStateOf(buildState())
        private set

    fun selectTrip(tripId: String) {
        repository.selectTrip(tripId)
        uiState = buildState()
    }

    fun createTrip(title: String) {
        repository.createTrip(title)
        uiState = buildState()
    }

    fun addParticipant(name: String) {
        val trip = uiState.trip ?: return
        if (name.isBlank()) return
        repository.addParticipant(trip.id, name)
        uiState = buildState()
    }

    fun addExpense(label: String, amount: String, payerId: String) {
        val trip = uiState.trip ?: return
        val parsedAmount = amount.replace(',', '.').toDoubleOrNull() ?: return
        if (label.isBlank() || parsedAmount <= 0.0 || payerId.isBlank()) return
        repository.addExpense(trip.id, label, parsedAmount, payerId)
        uiState = buildState()
    }

    fun toggleReceiptAssignment(itemId: String, participantId: String) {
        val trip = uiState.trip ?: return
        val item = trip.receipt?.items?.firstOrNull { it.id == itemId } ?: return
        repository.setReceiptItemAssignment(
            tripId = trip.id,
            itemId = itemId,
            participantId = participantId,
            assigned = participantId !in item.assignedParticipantIds
        )
        uiState = buildState()
    }

    fun importScannedReceipt(rawText: String, payerId: String) {
        val trip = uiState.trip ?: return
        val items = parseReceiptText(rawText)
        if (items.isEmpty()) {
            uiState = buildState(scanError = "Aucun article avec prix n'a ete detecte sur le ticket.")
            return
        }
        repository.importReceipt(trip.id, payerId, rawText, items)
        uiState = buildState()
    }

    fun showScanError(message: String) {
        uiState = buildState(scanError = message)
    }

    private fun buildState(scanError: String? = null): FairShareUiState {
        val trips = repository.getTrips()
        val selectedTripId = repository.getSelectedTripId()
        val trip = trips.firstOrNull { it.id == selectedTripId } ?: trips.firstOrNull()
        val balances = trip?.let(calculateBalances).orEmpty()
        return FairShareUiState(
            trips = trips,
            selectedTripId = trip?.id,
            trip = trip,
            balances = balances,
            settlements = calculateSettlements(balances),
            scanError = scanError
        )
    }
}
