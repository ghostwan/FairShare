package com.fairshare.presentation.invite

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.invitation.ExportException
import com.fairshare.data.invitation.InvitationExporter
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the "Invite" screen.
 *
 * Builds a single compact URL carrying just the eventId + 32-byte
 * encryption key. Anyone with the URL can read and write the event —
 * treat it like a secret. The joining device fetches the full history
 * from the Cloudflare Worker on first sync.
 */
data class InviteState(
    val loading: Boolean = true,
    val url: String? = null,
    val error: String? = null,
)

@HiltViewModel
class InviteViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exporter: InvitationExporter,
) : ViewModel() {

    val eventId: String = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])

    private val _state = MutableStateFlow(InviteState())
    val state: StateFlow<InviteState> = _state.asStateFlow()

    init {
        regenerate()
    }

    fun regenerate() {
        _state.value = InviteState(loading = true)
        viewModelScope.launch {
            exporter.export(eventId)
                .onSuccess { export ->
                    _state.value = InviteState(
                        loading = false,
                        url = export.url,
                    )
                }
                .onFailure { t ->
                    val message = when ((t as? ExportException)?.error) {
                        InvitationExporter.ExportError.EventNotFound ->
                            "Évènement introuvable."
                        InvitationExporter.ExportError.EncryptionKeyMissing ->
                            "Cet évènement n'a pas de clé locale. Recrée-le pour " +
                                "en générer une."
                        null -> t.message ?: "Erreur inconnue"
                    }
                    _state.value = InviteState(loading = false, error = message)
                }
        }
    }
}
