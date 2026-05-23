package com.fairshare.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fairshare.app.data.InMemoryTripRepository
import com.fairshare.app.domain.CalculateBalancesUseCase
import com.fairshare.app.domain.CalculateSettlementsUseCase
import com.fairshare.app.domain.model.Balance
import com.fairshare.app.domain.model.Expense
import com.fairshare.app.domain.model.Participant
import com.fairshare.app.domain.model.ReceiptItem
import java.text.NumberFormat
import java.util.Locale

private val Cream = Color(0xFFF7F2EA)
private val Ink = Color(0xFF111827)
private val Muted = Color(0xFF6B7280)
private val Teal = Color(0xFF0F766E)
private val Amber = Color(0xFFF59E0B)

@Composable
fun FairShareApp(viewModel: FairShareViewModel) {
    FairShareTheme {
        FairShareScreen(
            state = viewModel.uiState,
            onScanReceipt = viewModel::scanExampleReceipt,
            onToggleAssignment = viewModel::toggleReceiptAssignment
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun FairShareScreen(
    state: FairShareUiState,
    onScanReceipt: () -> Unit,
    onToggleAssignment: (itemId: String, participantId: String) -> Unit
) {
    val money = NumberFormat.getCurrencyInstance(Locale.FRANCE)
    val assignedCount = state.trip.receipt.items.count { it.assignedParticipantIds.isNotEmpty() }
    val eventTotal = state.trip.expenses.sumOf { it.amount } +
        state.trip.receipt.items.filter { it.assignedParticipantIds.isNotEmpty() }.sumOf { it.amount }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text(state.trip.title, color = Muted, fontWeight = FontWeight.Bold)
        Text("FairShare", color = Ink, fontSize = 38.sp, fontWeight = FontWeight.Black)
        Text(
            "Partage les depenses et assigne chaque plat du ticket aux bonnes personnes.",
            color = Muted,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(16.dp))

        AppCard(title = "Participants") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.trip.participants.forEach { participant ->
                    ParticipantPill(participant.name, selected = true)
                }
            }
        }

        AppCard(title = "Vue d'ensemble") {
            Metric("Total evenement", money.format(eventTotal))
            Metric("Ticket restaurant", money.format(state.trip.receipt.items.sumOf { it.amount }))
            Metric("Articles assignes", "$assignedCount / ${state.trip.receipt.items.size}")
            Spacer(Modifier.height(8.dp))
            Text(
                "Le ticket est paye par Alice. Une ligne non assignee reste en attente et n'entre pas dans le bilan.",
                color = Muted
            )
        }

        AppCard(title = "Depenses classiques") {
            state.trip.expenses.forEach { expense ->
                ExpenseRow(expense, state.trip.participants, money)
            }
        }

        AppCard(title = "Scan ticket de caisse") {
            Text(
                "Demo OCR: le scan transforme le ticket en articles cliquables. Selectionne les personnes qui ont consomme chaque plat ou boisson.",
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onScanReceipt,
                colors = ButtonDefaults.buttonColors(containerColor = Teal),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Scanner un ticket exemple")
            }
            Spacer(Modifier.height(10.dp))
            state.trip.receipt.items.forEach { item ->
                ReceiptItemRow(
                    item = item,
                    participants = state.trip.participants,
                    money = money,
                    onToggleAssignment = onToggleAssignment
                )
            }
        }

        AppCard(title = "Qui doit quoi ?") {
            state.balances.forEach { balance ->
                BalanceRow(balance, money)
            }
            Spacer(Modifier.height(14.dp))
            Text("Reglements proposes", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (state.settlements.isEmpty()) {
                Text("Tout est equilibre.", color = Muted)
            } else {
                state.settlements.forEach { settlement ->
                    Text(
                        "${settlement.from.name} rembourse ${money.format(settlement.amount)} a ${settlement.to.name}",
                        color = Muted
                    )
                }
            }
        }
    }
}

@Composable
private fun AppCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Muted, modifier = Modifier.weight(1f))
        Text(value, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ExpenseRow(expense: Expense, participants: List<Participant>, money: NumberFormat) {
    val payer = participants.first { it.id == expense.payerId }
    Column(Modifier.padding(vertical = 7.dp)) {
        Text(expense.label, color = Ink, fontWeight = FontWeight.Bold)
        Text("${payer.name} a paye ${money.format(expense.amount)}", color = Muted)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReceiptItemRow(
    item: ReceiptItem,
    participants: List<Participant>,
    money: NumberFormat,
    onToggleAssignment: (itemId: String, participantId: String) -> Unit
) {
    val assignedNames = participants
        .filter { it.id in item.assignedParticipantIds }
        .joinToString { it.name }

    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.label, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(money.format(item.amount), color = Ink, fontWeight = FontWeight.Bold)
        }
        Text(
            text = if (assignedNames.isBlank()) "En attente d'assignation" else "Partage: $assignedNames",
            color = if (assignedNames.isBlank()) Amber else Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            participants.forEach { participant ->
                val selected = participant.id in item.assignedParticipantIds
                FilterChip(
                    selected = selected,
                    onClick = { onToggleAssignment(item.id, participant.id) },
                    label = { Text(participant.name) }
                )
            }
        }
    }
}

@Composable
private fun BalanceRow(balance: Balance, money: NumberFormat) {
    val status = if (balance.amount >= 0) "doit recevoir" else "doit payer"
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(balance.participant.name, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("$status ${money.format(kotlin.math.abs(balance.amount))}", color = Muted)
    }
}

@Composable
private fun ParticipantPill(label: String, selected: Boolean) {
    Surface(
        color = if (selected) Teal else Color(0xFFE5E7EB),
        contentColor = if (selected) Color.White else Ink,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FairShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Preview(showBackground = true)
@Composable
private fun FairSharePreview() {
    FairShareApp(
        viewModel = FairShareViewModel(
            repository = InMemoryTripRepository(),
            calculateBalances = CalculateBalancesUseCase(),
            calculateSettlements = CalculateSettlementsUseCase()
        )
    )
}
