package com.fairshare.presentation.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.sync.ExportException
import com.fairshare.data.sync.SneakernetExporter
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the "Share changes" screen.
 *
 * The screen exports the *whole* op log of the current event into a
 * single sneakernet URL on first composition. Once produced, the user
 * can copy it to the clipboard or fire a standard Share Intent
 * (WhatsApp, Signal, mail, …). QR rendering ships in a follow-up
 * commit.
 *
 * Empty / error states are surfaced verbatim — the user can decide
 * what to do (typically: add data first, or re-create the event so a
 * fresh encryption key is generated).
 */
data class ShareChangesState(
    val loading: Boolean = true,
    val url: String? = null,
    val opCount: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class ShareChangesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exporter: SneakernetExporter,
) : ViewModel() {

    val eventId: String = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])

    private val _state = MutableStateFlow(ShareChangesState())
    val state: StateFlow<ShareChangesState> = _state.asStateFlow()

    init {
        regenerate()
    }

    fun regenerate() {
        _state.value = ShareChangesState(loading = true)
        viewModelScope.launch {
            exporter.export(eventId)
                .onSuccess { export ->
                    _state.value = ShareChangesState(
                        loading = false,
                        url = export.url,
                        opCount = export.opCount,
                    )
                }
                .onFailure { t ->
                    val message = when ((t as? ExportException)?.error) {
                        SneakernetExporter.ExportError.EventNotFound ->
                            "Évènement introuvable."
                        SneakernetExporter.ExportError.EncryptionKeyMissing ->
                            "Cet évènement n'a pas de clé locale. Recrée-le pour " +
                                "en générer une."
                        null -> t.message ?: "Erreur inconnue"
                    }
                    _state.value = ShareChangesState(loading = false, error = message)
                }
        }
    }
}
