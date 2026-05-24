package com.fairshare.presentation.expense

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fairshare.presentation.common.toMediumDateLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    onDone: () -> Unit,
    vm: AddExpenseViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val participants by vm.participants.collectAsState()
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    // Pre-select everyone for *new* expenses only; edit mode keeps the saved selection.
    LaunchedEffect(participants, state.isEditMode, state.isLoading) {
        if (!state.isEditMode && !state.isLoading) {
            if (state.selectedIds.isEmpty() && participants.isNotEmpty()) vm.selectAll()
            if (state.payerId == null && participants.isNotEmpty()) vm.setPayer(participants.first().id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditMode) "Modifier la dépense" else "Nouvelle dépense") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    if (state.isEditMode) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Supprimer")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
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
                // Read-only field whose only action is to open the
                // DatePickerDialog. Putting it after the amount keeps
                // the data-entry flow linear: what, how much, when.
                OutlinedTextField(
                    value = state.dateMillis.toMediumDateLabel(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Date") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = "Choisir une date")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true },
                )
            }
            item { Text("Payé par", style = MaterialTheme.typography.titleSmall) }
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
                item { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Button(
                    onClick = { vm.save(onDone) },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isSaving) "…" else if (state.isEditMode) "Mettre à jour" else "Enregistrer")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Supprimer la dépense ?") },
            text = { Text("Cette action est irréversible.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.delete(onDone)
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Annuler") } },
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let(vm::setDate)
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Annuler") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
