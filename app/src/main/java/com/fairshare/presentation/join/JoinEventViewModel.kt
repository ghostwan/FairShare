package com.fairshare.presentation.join

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.invitation.ImportException
import com.fairshare.data.invitation.InvitationImporter
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64
import javax.inject.Inject

/**
 * UI state for the "Join event" confirmation screen.
 *
 * Reached either from a `fairshare://join` deep link (intent-filter
 * on [com.fairshare.MainActivity]) or from the in-app QR scanner.
 * Either way the encoded URL is passed in the nav arg and decoded
 * once on init. The screen shows minimal context (event name + op
 * count) so the user can verify they're joining the right event;
 * accepting funnels the seed ops through [InvitationImporter.apply]
 * which materializes them locally and queues a push to the Worker.
 */
data class JoinEventState(
    val loading: Boolean = true,
    val eventName: String? = null,
    val eventId: String? = null,
    val opCount: Int = 0,
    val joining: Boolean = false,
    val joined: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class JoinEventViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importer: InvitationImporter,
) : ViewModel() {

    private val encodedLink: String = checkNotNull(savedStateHandle[Route.ARG_DEEP_LINK])
    private val url: String = String(
        Base64.getUrlDecoder().decode(encodedLink),
        Charsets.UTF_8,
    )

    private val _state = MutableStateFlow(JoinEventState())
    val state: StateFlow<JoinEventState> = _state.asStateFlow()

    init {
        loadPreview()
    }

    private fun loadPreview() {
        _state.value = JoinEventState(loading = true)
        viewModelScope.launch {
            importer.preview(url)
                .onSuccess { p ->
                    _state.value = JoinEventState(
                        loading = false,
                        eventName = p.eventName,
                        eventId = p.eventId,
                        opCount = p.ops.size,
                    )
                }
                .onFailure { t ->
                    _state.value = JoinEventState(loading = false, error = mapError(t))
                }
        }
    }

    fun join() {
        if (_state.value.joining || _state.value.joined) return
        _state.value = _state.value.copy(joining = true, error = null)
        viewModelScope.launch {
            importer.apply(url)
                .onSuccess {
                    _state.value = _state.value.copy(joining = false, joined = true)
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(joining = false, error = mapError(t))
                }
        }
    }

    private fun mapError(t: Throwable): String = when ((t as? ImportException)?.error) {
        InvitationImporter.ImportError.MalformedUrl -> "Lien d'invitation malformé."
        InvitationImporter.ImportError.MissingFields -> "Lien d'invitation incomplet."
        InvitationImporter.ImportError.SignatureMismatch ->
            "Signature invalide. Lien altéré ou clé corrompue."
        is InvitationImporter.ImportError.PayloadInvalid -> "Données invalides dans le lien."
        null -> t.message ?: "Erreur inconnue."
    }
}
