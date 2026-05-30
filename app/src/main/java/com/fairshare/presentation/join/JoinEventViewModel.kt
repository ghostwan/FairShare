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
 * Reached either from a deep link (intent-filter on
 * [com.fairshare.MainActivity]) or from the in-app QR scanner. Either
 * way the encoded URL is passed in the nav arg and decoded once on
 * init. The screen shows minimal context (event name when already
 * known) so the user can verify they're joining the right event;
 * accepting triggers [InvitationImporter.apply] which inserts a
 * placeholder event row, registers for push, and pulls the full
 * history from the Worker.
 */
data class JoinEventState(
    val loading: Boolean = true,
    val eventName: String? = null,
    val eventId: String? = null,
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
                .onSuccess { p ->
                    _state.value = _state.value.copy(
                        joining = false,
                        joined = true,
                        eventName = p.eventName ?: _state.value.eventName,
                    )
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(joining = false, error = mapError(t))
                }
        }
    }

    private fun mapError(t: Throwable): String = when ((t as? ImportException)?.error) {
        InvitationImporter.ImportError.MalformedUrl -> "Lien d'invitation malformé."
        InvitationImporter.ImportError.MissingFields -> "Lien d'invitation incomplet."
        null -> t.message ?: "Erreur inconnue."
    }
}
