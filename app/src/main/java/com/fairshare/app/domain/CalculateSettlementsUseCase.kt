package com.fairshare.app.domain

import com.fairshare.app.domain.model.Balance
import com.fairshare.app.domain.model.Settlement

class CalculateSettlementsUseCase {
    operator fun invoke(balances: List<Balance>): List<Settlement> {
        val debtors = balances
            .filter { it.amount < -0.01 }
            .map { MutableDebt(it.participant, -it.amount) }
            .toMutableList()
        val creditors = balances
            .filter { it.amount > 0.01 }
            .map { MutableDebt(it.participant, it.amount) }
            .toMutableList()

        val settlements = mutableListOf<Settlement>()
        var debtorIndex = 0
        var creditorIndex = 0

        while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
            val debtor = debtors[debtorIndex]
            val creditor = creditors[creditorIndex]
            val transfer = minOf(debtor.amount, creditor.amount).roundMoney()

            settlements += Settlement(from = debtor.participant, to = creditor.participant, amount = transfer)
            debtor.amount = (debtor.amount - transfer).roundMoney()
            creditor.amount = (creditor.amount - transfer).roundMoney()

            if (debtor.amount <= 0.01) debtorIndex++
            if (creditor.amount <= 0.01) creditorIndex++
        }

        return settlements
    }
}

private data class MutableDebt(
    val participant: com.fairshare.app.domain.model.Participant,
    var amount: Double
)
