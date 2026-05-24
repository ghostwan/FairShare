package com.fairshare.presentation.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fairshare.domain.model.Category
import com.fairshare.presentation.common.toMediumDateLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    onDone: () -> Unit,
    vm: AddExpenseViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val participants by vm.participants.collectAsState()
    val categories by vm.categories.collectAsState()
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showAddCategory by rememberSaveable { mutableStateOf(false) }

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
            item { Text("Catégorie", style = MaterialTheme.typography.titleSmall) }
            item {
                CategoryChipsRow(
                    categories = categories,
                    selectedId = state.categoryId,
                    onSelect = vm::setCategory,
                    onAddNew = { showAddCategory = true },
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

    if (showAddCategory) {
        AddCategoryDialog(
            onDismiss = { showAddCategory = false },
            onConfirm = { name, emoji, color ->
                vm.addCustomCategory(name, emoji, color)
                showAddCategory = false
            },
        )
    }
}

/**
 * Horizontally-scrollable filter chip row. The leading "Aucune" chip
 * clears the selection; the trailing "+" chip opens the create dialog.
 * Defaults and custom categories share the same chip shape; the only
 * visual differentiator is the leading emoji.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChipsRow(
    categories: List<Category>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onAddNew: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedId == null,
                onClick = { onSelect(null) },
                label = { Text("Aucune") },
            )
        }
        items(categories, key = { it.id }) { cat ->
            val tint = Color(cat.color)
            FilterChip(
                selected = selectedId == cat.id,
                onClick = { onSelect(cat.id) },
                label = { Text("${cat.emoji} ${cat.name}") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = tint.copy(alpha = 0.20f),
                ),
            )
        }
        item {
            AssistChip(
                onClick = onAddNew,
                label = { Text("Nouvelle") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
            )
        }
    }
}

/**
 * Minimal create-category dialog: name (required), single emoji
 * (defaults to a generic icon) and one of 8 pre-picked colors. Keeps
 * the scope of Commit B tight; an "edit/delete custom" management
 * screen can come later if needed.
 */
@Composable
private fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, emoji: String, color: Long) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf("🏷️") }
    val palette = listOf(
        0xFFE53935L, 0xFFEF6C00L, 0xFFFFB300L, 0xFF66BB6A,
        0xFF00897BL, 0xFF1E88E5L, 0xFF8E24AA, 0xFF607D8BL,
    )
    var selectedColor by rememberSaveable { mutableStateOf(palette.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle catégorie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { v ->
                        // Keep at most ~2 chars to allow VS16-emoji like 🍽️.
                        emoji = v.take(4)
                    },
                    label = { Text("Emoji") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Couleur", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(palette) { c ->
                        val color = Color(c)
                        val selected = selectedColor == c
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { selectedColor = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, emoji.ifBlank { "🏷️" }, selectedColor) },
                enabled = name.isNotBlank(),
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
