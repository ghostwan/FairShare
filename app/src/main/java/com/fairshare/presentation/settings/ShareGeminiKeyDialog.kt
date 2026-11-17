package com.fairshare.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fairshare.data.settings.GeminiKeyCodec
import com.fairshare.data.sync.QrCodeGenerator

/**
 * Dialog showing a QR encoding the user's Gemini API key + model so it
 * can be copied to another device (phone, tablet, web PWA) by scanning
 * with the in-app scanner. The QR is never uploaded; it's rendered
 * locally from [apiKey] and [model] and discarded on dismiss.
 *
 * The raw key is also displayed (selectable) with copy / system-share
 * actions so it can be handed to a friend through any messaging app.
 */
@Composable
fun ShareGeminiKeyDialog(
    apiKey: String,
    model: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val url = remember(apiKey, model) {
        runCatching { GeminiKeyCodec.encode(apiKey, model) }.getOrNull()
    }
    val bitmap = remember(url) {
        url?.let { runCatching { QrCodeGenerator.generate(it, sizePx = 1024) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        title = { Text("Partager la clé Gemini") },
        text = {
            if (bitmap != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(PaddingValues(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR clé Gemini",
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    }
                    SelectionContainer {
                        Text(
                            apiKey,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (!model.isNullOrBlank()) {
                        Text(
                            "Modèle : $model",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyKey(context, apiKey) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Text("  Copier")
                        }
                        OutlinedButton(onClick = { shareKey(context, apiKey, model) }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Text("  Partager…")
                        }
                    }
                    Text(
                        "Toute personne ayant cette clé consomme ton quota Gemini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Renseigne d'abord une clé Gemini dans le champ ci-dessus.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}

private fun copyKey(context: Context, key: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Gemini API key", key))
}

private fun shareKey(context: Context, key: String, model: String?) {
    val text = buildString {
        append("Clé API Gemini pour FairShare : ").append(key)
        if (!model.isNullOrBlank()) append("\nModèle : ").append(model)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}
