package com.fairshare.presentation.events

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fairshare.domain.model.Event

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EventsScreen(
    onOpenEvent: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onScanInvitation: () -> Unit = {},
    vm: EventsViewModel = hiltViewModel(),
) {
    val events by vm.events.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Event?>(null) }

    // Trigger a sync every time the screen comes back to the foreground.
    // Cheap when nothing changed (no ops to push, empty pull page).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FairShare") },
                actions = {
                    IconButton(onClick = onScanInvitation) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scanner une invitation")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Réglages")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Nouvel événement") },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Groups, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text("Pas encore d'événement", style = MaterialTheme.typography.titleMedium)
                        Text("Crée ton premier voyage ou repas partagé.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(events, key = { it.id }) { e ->
                        ElevatedCard(
                            modifier = Modifier.combinedClickable(
                                onClick = { onOpenEvent(e.id) },
                                onLongClick = { pendingDelete = e },
                            ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(e.name, style = MaterialTheme.typography.titleMedium)
                                if (!e.description.isNullOrBlank()) {
                                    Text(e.description, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(e.currency, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateEventDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, currency, participants ->
                vm.createEvent(name, currency, participants)
                showCreate = false
            },
        )
    }

    pendingDelete?.let { ev ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Supprimer l'événement ?") },
            text = {
                Text(
                    "« ${ev.name} » et toutes ses dépenses seront supprimés " +
                        "sur cet appareil. Les autres appareils gardent leur copie.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEvent(ev.id)
                    pendingDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun CreateEventDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, List<String>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("EUR") }
    var participantsText by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvel événement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nom (ex. Week-end Lisbonne)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = currency, onValueChange = { currency = it.uppercase().take(3) },
                    label = { Text("Devise") }, singleLine = true,
                )
                OutlinedTextField(
                    value = participantsText, onValueChange = { participantsText = it },
                    label = { Text("Participants (séparés par virgule)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name, currency.ifBlank { "EUR" }, participantsText.split(','))
                },
                enabled = name.isNotBlank(),
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
