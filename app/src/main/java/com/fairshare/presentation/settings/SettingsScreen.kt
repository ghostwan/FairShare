package com.fairshare.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val expand by vm.expandQuantities.collectAsState()
    val apiKey by vm.geminiApiKey.collectAsState()
    val model by vm.geminiModel.collectAsState()
    val cloudUrl by vm.cloudBaseUrl.collectAsState()
    val autoRefresh by vm.autoRefreshEnabled.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    var showKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Scan de ticket",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            ListItem(
                headlineContent = { Text("Une ligne par unité") },
                supportingContent = {
                    Text(
                        if (expand)
                            "« 2 × Bière 11,00 » est éclaté en 2 articles à 5,50 € — chacun assignable à une personne différente."
                        else
                            "« 2 × Bière 11,00 » reste une seule ligne — tu coches les personnes qui partagent la dépense (split équitable)."
                    )
                },
                trailingContent = {
                    Switch(checked = expand, onCheckedChange = vm::setExpandQuantities)
                },
            )

            Text(
                "Fallback IA (Gemini)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp),
            )
            Text(
                "Utilisé via le bouton « Réessayer avec IA » quand l'OCR local donne un mauvais résultat. Laisse vide pour désactiver.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = vm::setGeminiApiKey,
                label = { Text("Clé API Gemini") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            null,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = model,
                onValueChange = vm::setGeminiModel,
                label = { Text("Modèle Gemini") },
                singleLine = true,
                supportingText = { Text("Ex. gemini-2.5-flash, gemini-2.5-pro") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Text(
                "Synchronisation cloud",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp),
            )
            Text(
                "Les ops chiffrées (AES-256-GCM) sont relayées par un Worker Cloudflare. La clé reste sur tes appareils — laisse l'URL par défaut sauf si tu héberges ton propre Worker.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ListItem(
                headlineContent = { Text("Rafraîchissement automatique") },
                supportingContent = {
                    Text(
                        if (autoRefresh)
                            "Les écrans interrogent le Worker toutes les 10 s en avant-plan. Désactive si ça consomme trop de batterie ou de données."
                        else
                            "Sync uniquement via le bouton « Synchroniser maintenant » ou tirer-pour-rafraîchir."
                    )
                },
                trailingContent = {
                    Switch(checked = autoRefresh, onCheckedChange = vm::setAutoRefreshEnabled)
                },
            )
            OutlinedTextField(
                value = cloudUrl,
                onValueChange = vm::setCloudBaseUrl,
                label = { Text("URL du Worker") },
                singleLine = true,
                supportingText = { Text("Vide = sync cloud désactivée") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = vm::syncNow, enabled = !syncStatus.isRunning) {
                    if (syncStatus.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Synchroniser maintenant")
                    }
                }
                OutlinedButton(
                    onClick = vm::resetCloudBaseUrl,
                    enabled = cloudUrl != vm.defaultCloudBaseUrl,
                ) { Text("Réinitialiser l'URL") }
            }
            Text(
                text = formatSyncStatus(syncStatus),
                style = MaterialTheme.typography.bodySmall,
                color = if (syncStatus.lastFailureCount > 0 || syncStatus.lastError != null)
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

private fun formatSyncStatus(status: CloudSyncStatus): String {
    if (status.isRunning) return "Synchronisation en cours…"
    val attempt = status.lastAttemptMs ?: return "Dernière sync : jamais"
    val ageSeconds = ((System.currentTimeMillis() - attempt) / 1000L).coerceAtLeast(0)
    val ageLabel = when {
        ageSeconds < 60 -> "à l'instant"
        ageSeconds < 3600 -> "il y a ${ageSeconds / 60} min"
        else -> "il y a ${ageSeconds / 3600} h"
    }
    val counts = "${status.lastSuccessCount} OK, ${status.lastFailureCount} échec(s)"
    val err = status.lastError?.let { " — $it" }.orEmpty()
    return "Dernière sync : $ageLabel · $counts$err"
}
