package com.fairshare.presentation.join

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
import androidx.compose.material.icons.filled.GroupAdd
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Confirmation screen reached after decoding a `fairshare://join`
 * invitation URL (deep link or scanned QR). Shows the event name and
 * the seed op count so the user can verify before tapping "Rejoindre",
 * which hands the bundle to the importer and, on success, navigates
 * to the event detail.
 *
 * Idempotent: re-tapping the same invitation is a no-op because the
 * applier dedupes ops on opId.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinEventScreen(
    onBack: () -> Unit,
    onJoined: (eventId: String) -> Unit,
    vm: JoinEventViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(state.joined) {
        val id = state.eventId
        if (state.joined && id != null) onJoined(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rejoindre un évènement") },
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
                state.error != null && state.eventId == null ->
                    Text(
                        state.error!!,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                else -> Body(state, onJoin = vm::join)
            }
        }
    }
}

@Composable
private fun Body(state: JoinEventState, onJoin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            state.eventName ?: state.eventId.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Invitation à rejoindre cet évènement. " +
                "La clé de chiffrement est dans le lien — n'accepte " +
                "que si tu fais confiance à la personne qui te l'a partagé. " +
                "L'historique sera récupéré depuis le serveur de sync " +
                "après acceptation.",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (state.joined) {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Default.Check, contentDescription = null)
                Text("  Rejoint")
            }
        } else {
            Button(
                onClick = onJoin,
                enabled = !state.joining,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.joining) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                }
                Icon(Icons.Default.GroupAdd, contentDescription = null)
                Text("  Rejoindre")
            }
        }
    }
}
