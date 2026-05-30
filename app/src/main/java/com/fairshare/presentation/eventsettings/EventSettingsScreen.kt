package com.fairshare.presentation.eventsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fairshare.domain.model.Category
import com.fairshare.domain.model.DefaultCategories
import com.fairshare.presentation.common.AddCategoryDialog

/**
 * Settings for a single event. Today: list / add / delete custom
 * categories. Default categories are shown read-only for reference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventSettingsScreen(
    onBack: () -> Unit,
    vm: EventSettingsViewModel = hiltViewModel(),
) {
    val custom by vm.customCategories.collectAsState()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages de l'événement") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Catégorie") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            sectionHeader("Catégories personnalisées")
            if (custom.isEmpty()) {
                item("empty-custom") {
                    Text(
                        "Aucune catégorie personnalisée pour cet événement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(custom, key = { it.id }) { cat ->
                    CategoryRow(
                        category = cat,
                        trailing = {
                            IconButton(onClick = { pendingDelete = cat.id }) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
            sectionHeader("Catégories par défaut")
            items(DefaultCategories.ALL, key = { it.id }) { cat ->
                CategoryRow(category = cat, trailing = null)
                HorizontalDivider()
            }
        }
    }

    if (showAdd) {
        AddCategoryDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, emoji, color ->
                vm.addCategory(name, emoji, color)
                showAdd = false
            },
        )
    }

    val deletingId = pendingDelete
    if (deletingId != null) {
        val target = custom.firstOrNull { it.id == deletingId }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Supprimer la catégorie ?") },
            text = {
                Text(
                    "« ${target?.let { "${it.emoji} ${it.name}" } ?: "?"} » sera retirée. " +
                        "Les dépenses qui l'utilisaient deviendront « sans catégorie ».",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCategory(deletingId)
                    pendingDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Annuler") }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionHeader(title: String) {
    item("header-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun CategoryRow(category: Category, trailing: (@Composable () -> Unit)?) {
    val tint = Color(category.color)
    ListItem(
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(category.emoji, style = MaterialTheme.typography.titleMedium)
            }
        },
        headlineContent = {
            Text(category.name, fontWeight = FontWeight.Medium)
        },
        trailingContent = trailing,
    )
}
