package com.fairshare.presentation.expense

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    onDone: () -> Unit,
    vm: AddExpenseViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val participants by vm.participants.collectAsState()

    LaunchedEffect(participants) {
        if (state.selectedIds.isEmpty() && participants.isNotEmpty()) vm.selectAll()
        if (state.payerId == null && participants.isNotEmpty()) vm.setPayer(participants.first().id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nouvelle dépense") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.title, onValueChange = vm::setTitle,
                    label = { Text("Titre (ex. Restaurant, Hôtel…)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = state.amountText, onValueChange = vm::setAmount,
                    label = { Text("Montant total") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("Payé par", style = MaterialTheme.typography.titleSmall)
            }
            items(participants, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    leadingContent = {
                        RadioButton(selected = state.payerId == p.id, onClick = { vm.setPayer(p.id) })
                    },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("Pour qui ?", style = MaterialTheme.typography.titleSmall)
            }
            items(participants, key = { "po-${it.id}" }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    leadingContent = {
                        Checkbox(checked = p.id in state.selectedIds, onCheckedChange = { vm.togglePayee(p.id) })
                    },
                )
            }
            state.error?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Button(
                    onClick = { vm.save(onDone) },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isSaving) "…" else "Enregistrer")
                }
            }
        }
    }
}
