package com.fairshare.app.data

import android.content.Context
import com.fairshare.app.domain.TripRepository
import com.fairshare.app.domain.model.Expense
import com.fairshare.app.domain.model.Participant
import com.fairshare.app.domain.model.Receipt
import com.fairshare.app.domain.model.ReceiptItem
import com.fairshare.app.domain.model.Trip
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LocalTripRepository(context: Context) : TripRepository {
    private val preferences = context.getSharedPreferences("fairshare", Context.MODE_PRIVATE)
    private var trips = loadTrips()
    private var selectedTripId = preferences.getString(KEY_SELECTED_TRIP_ID, null) ?: trips.firstOrNull()?.id

    override fun getTrips(): List<Trip> = trips

    override fun getSelectedTripId(): String? = selectedTripId

    override fun selectTrip(tripId: String) {
        selectedTripId = tripId
        persist()
    }

    override fun createTrip(title: String): Trip {
        val trip = Trip(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Nouvel evenement" },
            participants = emptyList(),
            expenses = emptyList(),
            receipt = null
        )
        trips = trips + trip
        selectedTripId = trip.id
        persist()
        return trip
    }

    override fun addParticipant(tripId: String, name: String): Trip {
        return updateTrip(tripId) { trip ->
            trip.copy(participants = trip.participants + Participant(UUID.randomUUID().toString(), name.trim()))
        }
    }

    override fun addExpense(tripId: String, label: String, amount: Double, payerId: String): Trip {
        return updateTrip(tripId) { trip ->
            val participantIds = trip.participants.map { it.id }.toSet()
            trip.copy(
                expenses = trip.expenses + Expense(
                    id = UUID.randomUUID().toString(),
                    label = label.trim(),
                    amount = amount,
                    payerId = payerId,
                    participantIds = participantIds
                )
            )
        }
    }

    override fun importReceipt(tripId: String, payerId: String, rawText: String, items: List<ReceiptItem>): Trip {
        return updateTrip(tripId) { trip ->
            trip.copy(
                receipt = Receipt(
                    id = UUID.randomUUID().toString(),
                    merchant = rawText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifBlank { "Ticket scanne" },
                    payerId = payerId,
                    items = items
                )
            )
        }
    }

    override fun setReceiptItemAssignment(tripId: String, itemId: String, participantId: String, assigned: Boolean): Trip {
        return updateTrip(tripId) { trip ->
            val receipt = trip.receipt ?: return@updateTrip trip
            trip.copy(
                receipt = receipt.copy(
                    items = receipt.items.map { item ->
                        if (item.id != itemId) return@map item
                        item.copy(
                            assignedParticipantIds = if (assigned) {
                                item.assignedParticipantIds + participantId
                            } else {
                                item.assignedParticipantIds - participantId
                            }
                        )
                    }
                )
            )
        }
    }

    private fun updateTrip(tripId: String, transform: (Trip) -> Trip): Trip {
        var updated: Trip? = null
        trips = trips.map { trip ->
            if (trip.id == tripId) {
                transform(trip).also { updated = it }
            } else {
                trip
            }
        }
        persist()
        return requireNotNull(updated)
    }

    private fun loadTrips(): List<Trip> {
        val raw = preferences.getString(KEY_TRIPS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getJSONObject(index).toTrip() }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        preferences.edit()
            .putString(KEY_TRIPS, JSONArray(trips.map { it.toJson() }).toString())
            .putString(KEY_SELECTED_TRIP_ID, selectedTripId)
            .apply()
    }

    private fun JSONObject.toTrip(): Trip {
        return Trip(
            id = getString("id"),
            title = getString("title"),
            participants = getJSONArray("participants").toList { it.toParticipant() },
            expenses = getJSONArray("expenses").toList { it.toExpense() },
            receipt = optJSONObject("receipt")?.toReceipt()
        )
    }

    private fun Trip.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("participants", JSONArray(participants.map { it.toJson() }))
        .put("expenses", JSONArray(expenses.map { it.toJson() }))
        .put("receipt", receipt?.toJson())

    private fun JSONObject.toParticipant(): Participant = Participant(getString("id"), getString("name"))

    private fun Participant.toJson(): JSONObject = JSONObject().put("id", id).put("name", name)

    private fun JSONObject.toExpense(): Expense = Expense(
        id = getString("id"),
        label = getString("label"),
        amount = getDouble("amount"),
        payerId = getString("payerId"),
        participantIds = getJSONArray("participantIds").toStringSet()
    )

    private fun Expense.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("amount", amount)
        .put("payerId", payerId)
        .put("participantIds", JSONArray(participantIds.toList()))

    private fun JSONObject.toReceipt(): Receipt = Receipt(
        id = getString("id"),
        merchant = getString("merchant"),
        payerId = getString("payerId"),
        items = getJSONArray("items").toList { it.toReceiptItem() }
    )

    private fun Receipt.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("merchant", merchant)
        .put("payerId", payerId)
        .put("items", JSONArray(items.map { it.toJson() }))

    private fun JSONObject.toReceiptItem(): ReceiptItem = ReceiptItem(
        id = getString("id"),
        label = getString("label"),
        amount = getDouble("amount"),
        assignedParticipantIds = getJSONArray("assignedParticipantIds").toStringSet()
    )

    private fun ReceiptItem.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("amount", amount)
        .put("assignedParticipantIds", JSONArray(assignedParticipantIds.toList()))

    private fun <T> JSONArray.toList(mapper: (JSONObject) -> T): List<T> {
        return List(length()) { index -> mapper(getJSONObject(index)) }
    }

    private fun JSONArray.toStringSet(): Set<String> {
        return List(length()) { index -> getString(index) }.toSet()
    }

    private companion object {
        const val KEY_TRIPS = "trips"
        const val KEY_SELECTED_TRIP_ID = "selected_trip_id"
    }
}
