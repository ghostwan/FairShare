package com.fairshare.presentation.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.fairshare.data.settings.GeminiKeyCodec
import com.fairshare.data.sync.QrCodeGenerator

/**
 * Dialog showing a QR encoding the user's Gemini API key + model so it
 * can be copied to another device (phone, tablet, web PWA) by scanning
 * with the in-app scanner. The QR is never uploaded; it's rendered
 * locally from [apiKey] and [model] and discarded on dismiss.
 */
@Composable
fun ShareGeminiKeyDialog(
    apiKey: String,
    model: String?,
    onDismiss: () -> Unit,
) {
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
            } else {
                Text(
                    "Renseigne d'abord une clé Gemini dans le champ ci-dessus.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}
