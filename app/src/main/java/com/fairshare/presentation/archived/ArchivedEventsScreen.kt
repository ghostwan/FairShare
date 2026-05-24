package com.fairshare.presentation.archived

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fairshare.domain.model.Event

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArchivedEventsScreen(
    onBack: () -> Unit,
    vm: ArchivedEventsViewModel = hiltViewModel(),
) {
    val events by vm.events.collectAsState()
    var pending by remember { mutableStateOf<Pair<Event, PendingAction>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Événements archivés") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        if (events.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Inventory2,
                        null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Aucun événement archivé", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Appui long sur un événement pour l'archiver.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(events, key = { it.id }) { e ->
                    ElevatedCard(
                        modifier = Modifier.combinedClickable(
                            onClick = { pending = e to PendingAction.UNARCHIVE },
                            onLongClick = { pending = e to PendingAction.DELETE },
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(e.name, style = MaterialTheme.typography.titleMedium)
                            if (!e.description.isNullOrBlank()) {
                                Text(e.description, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                e.currency,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    pending?.let { (ev, action) ->
        when (action) {
            PendingAction.UNARCHIVE -> AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("Désarchiver l'événement ?") },
                text = {
                    Text(
                        "« ${ev.name} » sera de nouveau visible dans la liste " +
                            "principale, sur cet appareil et les autres.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.unarchive(ev.id)
                        pending = null
                    }) { Text("Désarchiver") }
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text("Annuler") }
                },
            )
            PendingAction.DELETE -> AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("Supprimer l'événement ?") },
                text = {
                    Text(
                        "« ${ev.name} » et toutes ses dépenses seront supprimés " +
                            "sur cet appareil. Les autres appareils gardent leur copie.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.delete(ev.id)
                        pending = null
                    }) { Text("Supprimer") }
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text("Annuler") }
                },
            )
        }
    }
}

private enum class PendingAction { UNARCHIVE, DELETE }
