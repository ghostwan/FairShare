package com.fairshare.app.domain

import com.fairshare.app.domain.model.Balance
import com.fairshare.app.domain.model.Trip
import kotlin.math.round

class CalculateBalancesUseCase {
    operator fun invoke(trip: Trip): List<Balance> {
        val balances = trip.participants.associate { it.id to 0.0 }.toMutableMap()

        trip.expenses.forEach { expense ->
            balances[expense.payerId] = balances.getValue(expense.payerId) + expense.amount
            val share = expense.amount / expense.participantIds.size
            expense.participantIds.forEach { participantId ->
                balances[participantId] = balances.getValue(participantId) - share
            }
        }

        trip.receipt?.items.orEmpty()
            .filter { it.assignedParticipantIds.isNotEmpty() }
            .forEach { item ->
                balances[trip.receipt.payerId] = balances.getValue(trip.receipt.payerId) + item.amount
                val share = item.amount / item.assignedParticipantIds.size
                item.assignedParticipantIds.forEach { participantId ->
                    balances[participantId] = balances.getValue(participantId) - share
                }
            }

        return trip.participants.map { participant ->
            Balance(participant = participant, amount = balances.getValue(participant.id).roundMoney())
        }
    }
}

internal fun Double.roundMoney(): Double = round(this * 100.0) / 100.0
