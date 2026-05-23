package com.fairshare.app.domain

import com.fairshare.app.domain.model.Trip

interface TripRepository {
    fun getTrips(): List<Trip>
    fun getSelectedTripId(): String?
    fun selectTrip(tripId: String)
    fun createTrip(title: String): Trip
    fun addParticipant(tripId: String, name: String): Trip
    fun addExpense(tripId: String, label: String, amount: Double, payerId: String): Trip
    fun importReceipt(tripId: String, payerId: String, rawText: String, items: List<com.fairshare.app.domain.model.ReceiptItem>): Trip
    fun setReceiptItemAssignment(tripId: String, itemId: String, participantId: String, assigned: Boolean): Trip
}
