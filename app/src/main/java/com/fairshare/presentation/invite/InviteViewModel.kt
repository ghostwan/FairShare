package com.fairshare.presentation.invite

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.invitation.ExportException
import com.fairshare.data.invitation.InvitationExporter
import com.fairshare.data.sync.SyncCoordinator
import com.fairshare.domain.repository.SettingsRepository
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    /**
     * True when the pre-share sync did not reach the Worker. The QR is
     * still rendered (the user may want it for later), but the joining
     * device will see nothing until this device pushes its local ops
     * — usually on the next foreground or "Synchroniser maintenant".
     */
    val syncWarning: Boolean = false,
    /** True when a Gemini key is configured locally and can be shared. */
    val hasGeminiKey: Boolean = false,
    /** When true, the URL also carries the local Gemini key + model. */
    val includeGeminiKey: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class InviteViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exporter: InvitationExporter,
    private val coordinator: SyncCoordinator,
    private val settings: SettingsRepository,
) : ViewModel() {

    val eventId: String = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])

    private val _state = MutableStateFlow(InviteState())
    val state: StateFlow<InviteState> = _state.asStateFlow()

    init {
        regenerate()
    }

    fun regenerate() {
        val include = _state.value.includeGeminiKey
        _state.value = InviteState(loading = true, includeGeminiKey = include)
        viewModelScope.launch {
            // CRITICAL: push the local op log to the Worker before
            // rendering the QR. Otherwise a joining device decodes the
            // URL, derives the bearer, hits the Worker — and gets
            // nothing back because we haven't pushed yet. The user
            // would see an empty event on the other side.
            //
            // syncEvent does push-then-pull; on a freshly created
            // event the push registers the bearer and uploads
            // EventUpsert + ParticipantUpserts in one round-trip. We
            // surface a non-blocking warning on failure (no network,
            // worker URL misconfigured) so the user knows the QR is
            // not immediately usable.
            val syncFailed = coordinator.syncEvent(eventId).isFailure
            buildUrl(syncWarning = syncFailed)
        }
    }

    /** Toggles whether the local Gemini key is embedded in the link. */
    fun setIncludeGeminiKey(include: Boolean) {
        if (_state.value.includeGeminiKey == include) return
        _state.value = _state.value.copy(includeGeminiKey = include)
        viewModelScope.launch { buildUrl(syncWarning = _state.value.syncWarning) }
    }

    private suspend fun buildUrl(syncWarning: Boolean) {
        val geminiKey = settings.geminiApiKey.first().trim()
        val geminiModel = settings.geminiModel.first().trim()
        val hasKey = geminiKey.isNotEmpty()
        val include = _state.value.includeGeminiKey && hasKey
        exporter.export(
            eventId = eventId,
            geminiKey = geminiKey.takeIf { include },
            geminiModel = geminiModel.takeIf { include && it.isNotEmpty() },
        )
            .onSuccess { export ->
                _state.value = InviteState(
                    loading = false,
                    url = export.url,
                    syncWarning = syncWarning,
                    hasGeminiKey = hasKey,
                    includeGeminiKey = include,
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
