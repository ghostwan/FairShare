package com.fairshare.domain.usecase

import com.fairshare.domain.model.Balance
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.Participant
import com.fairshare.domain.model.Settlement

/**
 * Pure function computing per-participant net balance from the list of expenses,
 * then a minimal-transactions settlement plan.
 */
class ComputeBalancesUseCase {

    fun balances(participants: List<Participant>, expenses: List<Expense>): List<Balance> {
        val net = mutableMapOf<Long, Long>().withDefault { 0L }
        expenses.forEach { e ->
            net[e.payerId] = (net.getValue(e.payerId)) + e.amountCents
            e.shares.forEach { s ->
                net[s.participantId] = (net.getValue(s.participantId)) - s.amountCents
            }
        }
        return participants.map { p ->
            Balance(p.id, p.name, net.getValue(p.id))
        }
    }

    /** Greedy minimal-transactions settlement. */
    fun settlements(balances: List<Balance>): List<Settlement> {
        // Work on a mutable copy
        val list = balances.map { it.copy() }.toMutableList()
        val result = mutableListOf<Settlement>()

        while (true) {
            val maxCreditor = list.maxByOrNull { it.netCents } ?: break
            val maxDebtor = list.minByOrNull { it.netCents } ?: break
            if (maxCreditor.netCents <= 0 || maxDebtor.netCents >= 0) break
            val transfer = minOf(maxCreditor.netCents, -maxDebtor.netCents)
            if (transfer <= 0) break
            result += Settlement(
                fromId = maxDebtor.participantId,
                fromName = maxDebtor.participantName,
                toId = maxCreditor.participantId,
                toName = maxCreditor.participantName,
                amountCents = transfer,
            )
            val i = list.indexOf(maxCreditor)
            val j = list.indexOf(maxDebtor)
            list[i] = maxCreditor.copy(netCents = maxCreditor.netCents - transfer)
            list[j] = maxDebtor.copy(netCents = maxDebtor.netCents + transfer)
        }
        return result
    }
}
