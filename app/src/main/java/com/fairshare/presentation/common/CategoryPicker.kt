package com.fairshare.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fairshare.domain.model.Category

/**
 * Horizontally-scrollable category picker shared by the manual expense form
 * and both receipt screens (scan + edit). The leading "Aucune" chip clears
 * the selection; the trailing "+" chip invokes [onAddNew] so the host can
 * open an [AddCategoryDialog]. Defaults and custom categories share the
 * same chip shape; the only visual differentiator is the leading emoji and
 * the selected-tint derived from the category color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryChipsRow(
    categories: List<Category>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onAddNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
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
 * Minimal create-category dialog reused across every "pick a category" entry
 * point. Asks for a name (required), a single emoji (default 🏷️) and one of
 * 8 pre-picked colors. Editing / deleting custom categories is intentionally
 * out of scope for this dialog.
 */
@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, emoji: String, color: Long) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf("🏷️") }
    val palette = listOf(
        0xFFE53935L, 0xFFEF6C00L, 0xFFFFB300L, 0xFF66BB6AL,
        0xFF00897BL, 0xFF1E88E5L, 0xFF8E24AAL, 0xFF607D8BL,
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
                        // Keep at most ~4 chars to allow VS16-emoji like 🍽️.
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
