package com.fairshare.presentation.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.settings.GeminiKeyCodec
import com.fairshare.domain.repository.SettingsRepository
import com.fairshare.presentation.scan.ScanQrScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Camera-based import flow for the Gemini API key. On the first
 * `fairshare://gemini?…` URL detected, decode it, persist key + model
 * to settings, show a confirmation dialog, then pop back.
 *
 * The key is intentionally never displayed on screen — only an
 * abbreviated form (length + prefix/suffix) is shown so the user can
 * sanity-check that the import landed without leaking the secret on a
 * screenshot.
 */
@Composable
fun ScanGeminiKeyScreen(
    onDone: () -> Unit,
    vm: ScanGeminiKeyViewModel = hiltViewModel(),
) {
    val result by vm.result.collectAsState()
    val scope = rememberCoroutineScope()

    // Auto-close after the dialog is shown long enough to read — leaving
    // the screen mounted would re-trigger scanning and re-show the
    // dialog if the user lingered with the QR in frame.
    LaunchedEffect(result) {
        if (result is ImportResult.Success) {
            // Dialog stays visible until user taps OK.
        }
    }

    when (val r = result) {
        ImportResult.Idle -> {
            ScanQrScreen(
                title = "Scanner une clé Gemini",
                accept = { GeminiKeyCodec.isGeminiKeyUrl(it) },
                onBack = onDone,
                onScanned = { url -> scope.launch { vm.consume(url) } },
            )
        }
        is ImportResult.Success -> {
            AlertDialog(
                onDismissRequest = onDone,
                confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
                icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                title = { Text("Clé Gemini importée") },
                text = {
                    Text(
                        buildString {
                            append("Clé : ").append(r.keyPreview)
                            if (r.model != null) {
                                append('\n').append("Modèle : ").append(r.model)
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
            )
        }
        is ImportResult.Error -> {
            AlertDialog(
                onDismissRequest = onDone,
                confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
                title = { Text("Import impossible") },
                text = { Text(r.message) },
            )
        }
    }
}

sealed interface ImportResult {
    data object Idle : ImportResult
    data class Success(val keyPreview: String, val model: String?) : ImportResult
    data class Error(val message: String) : ImportResult
}

@HiltViewModel
class ScanGeminiKeyViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _result = MutableStateFlow<ImportResult>(ImportResult.Idle)
    val result: StateFlow<ImportResult> = _result.asStateFlow()

    suspend fun consume(url: String) {
        if (_result.value !is ImportResult.Idle) return
        viewModelScope.launch {
            try {
                val decoded = GeminiKeyCodec.decode(url)
                settings.setGeminiApiKey(decoded.key)
                decoded.model?.let { settings.setGeminiModel(it) }
                _result.value = ImportResult.Success(
                    keyPreview = previewOf(decoded.key),
                    model = decoded.model,
                )
            } catch (e: IllegalArgumentException) {
                _result.value = ImportResult.Error(
                    "QR non reconnu (${e.message ?: "format invalide"})."
                )
            } catch (e: Exception) {
                _result.value = ImportResult.Error(
                    "Erreur d'enregistrement : ${e.message ?: e::class.simpleName}"
                )
            }
        }
    }

    private fun previewOf(key: String): String {
        if (key.length <= 8) return "•".repeat(key.length)
        return key.take(4) + "…" + key.takeLast(4) + " (${key.length} car.)"
    }
}
