package com.fairshare.presentation.eventdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fairshare.domain.model.Category
import com.fairshare.domain.model.DefaultCategories
import com.fairshare.presentation.common.centsToString
import com.fairshare.presentation.common.toDayHeaderLabel
import com.fairshare.presentation.common.toStartOfDay
import kotlinx.coroutines.launch

private enum class Tab(val label: String) {
    Expenses("Dépenses"),
    Balances("Soldes"),
    Stats("Stats"),
    Participants("Personnes"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    onBack: () -> Unit,
    onAddExpense: (String) -> Unit,
    onEditExpense: (eventId: String, expenseId: String) -> Unit,
    onScanReceipt: (String) -> Unit,
    onInvite: (String) -> Unit,
    onOpenEventSettings: (String) -> Unit,
    vm: EventDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val pagerState = rememberPagerState(initialPage = 0) { Tab.entries.size }
    val tab = Tab.entries[pagerState.currentPage]
    val pagerScope = rememberCoroutineScope()
    var showAddPerson by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    val currency = state.event?.currency ?: "EUR"

    // Catch up on any push notification missed while the screen was off.
    // Real-time updates otherwise arrive via FCM.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.event?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(
                        onClick = { showRename = true },
                        enabled = state.event != null,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Renommer")
                    }
                    IconButton(onClick = { onInvite(vm.eventId) }) {
                        Icon(Icons.Default.GroupAdd, contentDescription = "Inviter")
                    }
                    IconButton(
                        onClick = { onOpenEventSettings(vm.eventId) },
                        enabled = state.event != null,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Réglages de l'événement")
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
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    Tab.entries.forEachIndexed { index, t ->
                        androidx.compose.material3.Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { pagerScope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(t.label) },
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    // Each page must explicitly fill the pager slot,
                    // otherwise an intrinsically-sized child (e.g. a
                    // LazyColumn with few items) gets centered in the
                    // available height by the pager layout.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        when (Tab.entries[page]) {
                            Tab.Expenses -> if (state.loaded) ExpensesList(
                                state = state,
                                currency = currency,
                                onClick = { id -> onEditExpense(vm.eventId, id) },
                                onDelete = vm::removeExpense,
                            ) else Box(Modifier.fillMaxSize())
                            Tab.Balances -> if (state.loaded) BalancesList(state, currency, onSettle = vm::recordSettlement)
                                else Box(Modifier.fillMaxSize())
                            Tab.Stats -> if (state.loaded) StatsList(state, currency)
                                else Box(Modifier.fillMaxSize())
                            Tab.Participants -> if (state.loaded) ParticipantsList(
                                    state = state,
                                    currency = currency,
                                    onRemove = vm::removeParticipant,
                                    onRename = vm::renameParticipant,
                                )
                                else Box(Modifier.fillMaxSize())
                        }
                    }
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

    if (showRename) {
        // Pre-fill with the current name; cleared on dismiss via key().
        var name by rememberSaveable(state.event?.id) {
            mutableStateOf(state.event?.name.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Renommer l'événement") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != state.event?.name,
                    onClick = { vm.renameEvent(name); showRename = false },
                ) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Annuler") } },
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
    // Custom categories of this event, indexed by id. Default categories
    // are resolved from DefaultCategories.BY_ID at render time so the
    // map stays small and event-scoped.
    val customById = remember(state.customCategories) {
        state.customCategories.associateBy { it.id }
    }
    // Group by local day (start-of-day epoch) so timeline headers
    // collapse multiple expenses from the same day under one banner.
    // Most-recent day first. ExpenseDao already orders by date DESC,
    // but we re-sort defensively in case the upstream order changes.
    val groups = remember(state.expenses) {
        state.expenses
            .sortedByDescending { it.date }
            .groupBy { it.date.toStartOfDay() }
            .toList()
            .sortedByDescending { it.first }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (dayMillis, dayExpenses) ->
            item(key = "h-$dayMillis") {
                Text(
                    dayMillis.toDayHeaderLabel(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(dayExpenses, key = { it.id }) { e ->
                ElevatedCard(onClick = { onClick(e.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (e.isSettlement) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium)
                            val subtitle = if (e.isSettlement) {
                                val from = byId[e.payerId]?.name ?: "?"
                                val to = e.shares.firstOrNull()?.participantId
                                    ?.let { byId[it]?.name } ?: "?"
                                "Remboursement • $from → $to"
                            } else {
                                "Payé par ${byId[e.payerId]?.name ?: "?"} • ${e.shares.size} participants"
                            }
                            Text(subtitle, style = MaterialTheme.typography.bodySmall)
                            val category = e.categoryId?.let {
                                DefaultCategories.BY_ID[it] ?: customById[it]
                            }
                            if (category != null) {
                                Spacer(Modifier.height(4.dp))
                                CategoryBadge(category)
                            }
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
}

@Composable
private fun BalancesList(
    state: EventDetailState,
    currency: String,
    onSettle: (com.fairshare.domain.model.Settlement) -> Unit,
) {
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
                // Custom Card layout instead of ListItem because the
                // "Remboursé" TextButton in trailingContent overflowed
                // on narrow phones and overlapped the price line.
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${s.fromName} → ${s.toName}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                s.amountCents.centsToString(currency),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { onSettle(s) },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Remboursé")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsList(state: EventDetailState, currency: String) {
    val stats = state.categoryStats
    if (stats.isEmpty()) {
        EmptyHint("Aucune dépense à statistiquer.")
        return
    }
    val grandTotal = stats.sumOf { it.totalCents }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // Header line with the grand total so the user gets the
            // overall spend at a glance before diving into the bars.
            ListItem(
                headlineContent = {
                    Text("Total", style = MaterialTheme.typography.titleMedium)
                },
                trailingContent = {
                    Text(
                        grandTotal.centsToString(currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
        }
        items(stats, key = { it.category?.id ?: "uncategorized" }) { stat ->
            CategoryStatRow(stat, currency)
        }
    }
}

/**
 * One row of the Stats tab: emoji + name, count, total amount, and a
 * horizontal bar whose width is proportional to the bucket's share of
 * the grand total. Uncategorized expenses render with a neutral
 * outline tint and a "Sans catégorie" label.
 */
@Composable
private fun CategoryStatRow(
    stat: com.fairshare.domain.usecase.CategoryStat,
    currency: String,
) {
    val tint = stat.category?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline
    val label = stat.category?.let { "${it.emoji} ${it.name}" } ?: "Sans catégorie"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                stat.totalCents.centsToString(currency),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Track at low alpha, fill at full color sized
            // proportionally. fillMaxWidth(fraction) handles 0% / 100%
            // edge cases naturally.
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tint.copy(alpha = 0.15f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(stat.fraction.toFloat().coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(tint),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${(stat.fraction * 100).toInt()}% • ${stat.count}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ParticipantsList(
    state: EventDetailState,
    currency: String,
    onRemove: (String) -> Unit,
    onRename: (id: String, newName: String) -> Unit,
) {
    if (state.participants.isEmpty()) {
        EmptyHint("Aucun participant.")
        return
    }
    var renaming by remember { mutableStateOf<com.fairshare.domain.model.Participant?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(state.participants, key = { it.id }) { p ->
            val paid = state.paidByParticipant[p.id] ?: 0L
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = {
                    Text(
                        if (paid > 0L) "Total payé : ${paid.centsToString(currency)}"
                        else "Aucune dépense payée"
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { renaming = p }) {
                            Icon(Icons.Default.Edit, contentDescription = "Renommer")
                        }
                        IconButton(onClick = { onRemove(p.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }

    renaming?.let { target ->
        var name by rememberSaveable(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer le participant") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != target.name,
                    onClick = {
                        onRename(target.id, name)
                        renaming = null
                    },
                ) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Compact rounded tag rendering a [Category]: solid color background at
 * ~18% alpha so it doesn't fight the card surface, full-color text and
 * emoji prefix. Sized to read at a glance from the timeline.
 */
@Composable
private fun CategoryBadge(category: Category) {
    val tint = Color(category.color)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "${category.emoji} ${category.name}",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}
