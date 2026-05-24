package com.fairshare.presentation.eventdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fairshare.presentation.common.centsToString

private enum class Tab(val label: String) { Expenses("Dépenses"), Balances("Soldes"), Participants("Personnes") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    onBack: () -> Unit,
    onAddExpense: (String) -> Unit,
    onEditExpense: (eventId: String, expenseId: String) -> Unit,
    onScanReceipt: (String) -> Unit,
    onShareChanges: (String) -> Unit,
    vm: EventDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Expenses) }
    var showAddPerson by rememberSaveable { mutableStateOf(false) }
    val currency = state.event?.currency ?: "EUR"

    // Refresh from cloud every time the screen is resumed (back-nav,
    // process restore, etc). Cheap when there's nothing to do.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.event?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = { onShareChanges(vm.eventId) }) {
                        Icon(Icons.Default.Share, contentDescription = "Partager les changements")
                    }
                },
            )
        },
        floatingActionButton = {
            when (tab) {
                Tab.Expenses -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(onClick = { onScanReceipt(vm.eventId) }) {
                        Icon(Icons.Default.DocumentScanner, "Scanner un ticket")
                    }
                    ExtendedFloatingActionButton(
                        onClick = { onAddExpense(vm.eventId) },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Dépense") },
                    )
                }
                Tab.Participants -> ExtendedFloatingActionButton(
                    onClick = { showAddPerson = true },
                    icon = { Icon(Icons.Default.PersonAdd, null) },
                    text = { Text("Ajouter") },
                )
                else -> Unit
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    Tab.entries.forEach { t ->
                        androidx.compose.material3.Tab(
                            selected = tab == t,
                            onClick = { tab = t },
                            text = { Text(t.label) },
                        )
                    }
                }
                when (tab) {
                    Tab.Expenses -> ExpensesList(
                        state = state,
                        currency = currency,
                        onClick = { id -> onEditExpense(vm.eventId, id) },
                        onDelete = vm::removeExpense,
                    )
                    Tab.Balances -> BalancesList(state, currency)
                    Tab.Participants -> ParticipantsList(state, onRemove = vm::removeParticipant)
                }
            }
        }
    }

    if (showAddPerson) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddPerson = false },
            title = { Text("Ajouter un participant") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.addParticipant(name); showAddPerson = false
                }) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { showAddPerson = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ExpensesList(
    state: EventDetailState,
    currency: String,
    onClick: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (state.expenses.isEmpty()) {
        EmptyHint("Aucune dépense. Ajoute-en une ou scanne un ticket de caisse.")
        return
    }
    val byId = state.participants.associateBy { it.id }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.expenses, key = { it.id }) { e ->
            ElevatedCard(onClick = { onClick(e.id) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Payé par ${byId[e.payerId]?.name ?: "?"} • ${e.shares.size} participants",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        e.amountCents.centsToString(currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = { onDelete(e.id) }) { Icon(Icons.Default.Delete, null) }
                }
            }
        }
    }
}

@Composable
private fun BalancesList(state: EventDetailState, currency: String) {
    if (state.participants.isEmpty()) {
        EmptyHint("Ajoute des participants pour voir les soldes.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Soldes", style = MaterialTheme.typography.titleMedium)
        }
        items(state.balances, key = { it.participantId }) { b ->
            val isPositive = b.netCents >= 0
            ListItem(
                headlineContent = { Text(b.participantName) },
                supportingContent = {
                    Text(if (isPositive) "Doit recevoir" else "Doit payer")
                },
                trailingContent = {
                    Text(
                        (if (isPositive) "+" else "") + b.netCents.centsToString(currency),
                        color = if (isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
        }
        if (state.settlements.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Pour solder", style = MaterialTheme.typography.titleMedium)
            }
            items(state.settlements) { s ->
                ListItem(
                    headlineContent = { Text("${s.fromName} → ${s.toName}") },
                    trailingContent = { Text(s.amountCents.centsToString(currency), fontWeight = FontWeight.SemiBold) },
                )
            }
        }
    }
}

@Composable
private fun ParticipantsList(state: EventDetailState, onRemove: (String) -> Unit) {
    if (state.participants.isEmpty()) {
        EmptyHint("Aucun participant.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(state.participants, key = { it.id }) { p ->
            ListItem(
                headlineContent = { Text(p.name) },
                trailingContent = {
                    IconButton(onClick = { onRemove(p.id) }) { Icon(Icons.Default.Delete, null) }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
