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
 * Two link kinds are exposed via a [Mode] toggle (DESIGN.md §6.1):
 *
 *   - [Mode.SYNC]: a `fairshare://sync` URL carrying the whole op log,
 *     verified by HMAC against the recipient's existing event key.
 *     Targets devices that have already joined the event.
 *
 *   - [Mode.JOIN]: a `fairshare://join` invitation URL that also
 *     embeds the 32-byte event encryption key. Targets fresh devices
 *     that don't know the event yet. Anyone with this URL can read
 *     and write — treat it like a secret.
 *
 * The URL is regenerated whenever the mode changes. Empty op logs are
 * still valid (the recipient just sees no new ops).
 */
data class ShareChangesState(
    val mode: Mode = Mode.SYNC,
    val loading: Boolean = true,
    val url: String? = null,
    val opCount: Int = 0,
    val error: String? = null,
) {
    enum class Mode { SYNC, JOIN }
}

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

    fun setMode(mode: ShareChangesState.Mode) {
        if (_state.value.mode == mode) return
        _state.value = _state.value.copy(mode = mode)
        regenerate()
    }

    fun regenerate() {
        val mode = _state.value.mode
        _state.value = ShareChangesState(mode = mode, loading = true)
        viewModelScope.launch {
            val result = when (mode) {
                ShareChangesState.Mode.SYNC -> exporter.export(eventId)
                ShareChangesState.Mode.JOIN -> exporter.exportInvitation(eventId)
            }
            result
                .onSuccess { export ->
                    _state.value = ShareChangesState(
                        mode = mode,
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
                    _state.value = ShareChangesState(
                        mode = mode,
                        loading = false,
                        error = message,
                    )
                }
        }
    }
}
