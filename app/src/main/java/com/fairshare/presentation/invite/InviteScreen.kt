package com.fairshare.presentation.invite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fairshare.data.sync.QrCodeGenerator

/**
 * Minimal UI for the invitation export flow.
 *
 * Once the ViewModel has produced the invitation URL, the user can:
 *
 *   - scan the rendered QR code from another device,
 *   - copy the raw URL to the clipboard,
 *   - fire a system Share Intent (WhatsApp, Signal, mail, …).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteScreen(
    onBack: () -> Unit,
    vm: InviteViewModel = hiltViewModel(),
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
                title = { Text("Inviter") },
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
                    url = state.url!!,
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
    url: String,
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
        Text(
            "Le lien contient la clé de chiffrement : ne le partage " +
                "qu'avec des personnes de confiance. Le device qui rejoint " +
                "récupère l'historique depuis le serveur de sync.",
            style = MaterialTheme.typography.bodyMedium,
        )
        QrCodeBlock(content = url)
        OutlinedTextField(
            value = url,
            onValueChange = {},
            readOnly = true,
            label = { Text("Lien d'invitation") },
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

@Composable
private fun QrCodeBlock(content: String) {
    // Source bitmap at 1024px gives ~5-6 px per module for a version-40 QR
    // (177×177 modules), which downscales cleanly into the full-width display.
    val bitmap = remember(content) {
        runCatching { QrCodeGenerator.generate(content, sizePx = 1024) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR code",
                modifier = Modifier.fillMaxSize().aspectRatio(1f),
            )
        } else {
            Text(
                "QR code indisponible (payload trop long)",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black,
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("FairShare invitation", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}
