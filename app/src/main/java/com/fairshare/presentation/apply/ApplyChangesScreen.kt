package com.fairshare.presentation.apply

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fairshare.data.sync.SneakernetImporter

/**
 * Confirmation screen reached after tapping a `fairshare://sync` link
 * or after pasting one. Shows the bundle's contents (event name + op
 * count + per-op summary) so the user knows what they're about to
 * import, then a single Apply button hands the ops to
 * [com.fairshare.data.sync.OperationApplier].
 *
 * Idempotent by construction: the materializer ignores ops whose opId
 * already exists in the log, so re-tapping the same link is harmless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplyChangesScreen(
    onBack: () -> Unit,
    vm: ApplyChangesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appliquer les changements") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.eventId == null -> ErrorBody(state.error!!)
                else -> Body(state, onApply = vm::apply)
            }
        }
    }
}

@Composable
private fun ErrorBody(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Body(state: ApplyChangesState, onApply: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Évènement : ${state.eventName ?: state.eventId.orEmpty()}",
            style = MaterialTheme.typography.titleMedium,
        )
        val kindLabel = when (state.kind) {
            SneakernetImporter.Kind.JOIN -> "Invitation (la clé de l'évènement est dans le lien)"
            SneakernetImporter.Kind.SYNC -> "Synchronisation incrémentale"
            null -> null
        }
        kindLabel?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "${state.opCount} opération${if (state.opCount > 1) "s" else ""} à appliquer.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.previewLines.isNotEmpty()) {
            Text("Aperçu", style = MaterialTheme.typography.titleSmall)
            state.previewLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (state.opCount > state.previewLines.size) {
                Text(
                    "… et ${state.opCount - state.previewLines.size} autres.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (state.applied) {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Default.Check, contentDescription = null)
                Text("  Appliqué")
            }
        } else {
            Button(
                onClick = onApply,
                enabled = !state.applying && state.opCount > 0,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.applying) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                }
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("  Appliquer")
            }
        }
    }
}
