package com.fairshare.presentation.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Minimal UI for the sneakernet "Share changes" flow (DESIGN.md §6.1).
 *
 * Once the ViewModel has produced the bundle URL, the user can:
 *
 *   - read the raw URL in a read-only OutlinedTextField,
 *   - copy it to the clipboard,
 *   - fire a system Share Intent (WhatsApp, Signal, mail, …).
 *
 * QR rendering will be added in a follow-up commit (ZXing core
 * dependency + Canvas drawer). Same for the "Apply changes" landing
 * UI that consumes the URLs on the receiving device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareChangesScreen(
    onBack: () -> Unit,
    vm: ShareChangesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Partager les changements") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = vm::regenerate) {
                        Icon(Icons.Default.Refresh, contentDescription = "Régénérer")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.url != null -> Content(
                    mode = state.mode,
                    onModeChange = vm::setMode,
                    url = state.url!!,
                    opCount = state.opCount,
                    onCopy = { copyToClipboard(context, state.url!!) },
                    onShare = { shareText(context, state.url!!) },
                )
                else -> Text(
                    state.error ?: "Aucun contenu",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun Content(
    mode: ShareChangesState.Mode,
    onModeChange: (ShareChangesState.Mode) -> Unit,
    url: String,
    opCount: Int,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxSize()) {
            val modes = ShareChangesState.Mode.entries
            modes.forEachIndexed { index, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onModeChange(m) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(if (m == ShareChangesState.Mode.SYNC) "Sync" else "Invitation")
                }
            }
        }
        val helper = when (mode) {
            ShareChangesState.Mode.SYNC ->
                "Pour un appareil qui a déjà rejoint l'évènement. " +
                    "$opCount opération${if (opCount > 1) "s" else ""} — " +
                    "${url.length} caractères."
            ShareChangesState.Mode.JOIN ->
                "Invitation à rejoindre. Le lien contient la clé : ne le partage " +
                    "qu'avec des personnes de confiance. " +
                    "$opCount opération${if (opCount > 1) "s" else ""} dans la seed — " +
                    "${url.length} caractères."
        }
        Text(helper, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = url,
            onValueChange = {},
            readOnly = true,
            label = { Text(if (mode == ShareChangesState.Mode.SYNC) "Lien fairshare://sync" else "Lien fairshare://join") },
            modifier = Modifier.fillMaxSize(),
            maxLines = 8,
        )
        Button(onClick = onCopy, modifier = Modifier.fillMaxSize()) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Text("  Copier le lien")
        }
        Button(onClick = onShare, modifier = Modifier.fillMaxSize()) {
            Icon(Icons.Default.Share, contentDescription = null)
            Text("  Partager…")
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("FairShare sync", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}
