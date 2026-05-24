package com.fairshare.presentation.apply

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.sync.ImportException
import com.fairshare.data.sync.SneakernetImporter
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64
import javax.inject.Inject

/**
 * UI state for the "Apply changes" screen — the inbound side of the
 * sneakernet transport (DESIGN.md §6.1).
 *
 * The screen is reached either from a `fairshare://sync` deep link
 * (intent-filter on MainActivity) or, later, from a "paste a link"
 * affordance. Either way, the encoded URL is passed in the nav arg
 * and decoded once on init.
 *
 * Flow: decode → show preview (event name + op count + per-op summary)
 * → user confirms → ops are handed to [SneakernetImporter.apply],
 * which routes them through [com.fairshare.data.sync.OperationApplier]
 * with origin SNEAKERNET so they are inserted into the op log,
 * deduplicated, and materialized.
 */
data class ApplyChangesState(
    val loading: Boolean = true,
    val url: String? = null,
    val kind: SneakernetImporter.Kind? = null,
    val eventName: String? = null,
    val eventId: String? = null,
    val opCount: Int = 0,
    val previewLines: List<String> = emptyList(),
    val applying: Boolean = false,
    val applied: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ApplyChangesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importer: SneakernetImporter,
) : ViewModel() {

    private val encodedLink: String = checkNotNull(savedStateHandle[Route.ARG_DEEP_LINK])
    private val url: String = String(
        Base64.getUrlDecoder().decode(encodedLink),
        Charsets.UTF_8,
    )

    private val _state = MutableStateFlow(ApplyChangesState())
    val state: StateFlow<ApplyChangesState> = _state.asStateFlow()

    init {
        loadPreview()
    }

    private fun loadPreview() {
        _state.value = ApplyChangesState(loading = true, url = url)
        viewModelScope.launch {
            importer.preview(url)
                .onSuccess { p ->
                    _state.value = ApplyChangesState(
                        loading = false,
                        url = url,
                        kind = p.kind,
                        eventName = p.eventName,
                        eventId = p.eventId,
                        opCount = p.ops.size,
                        previewLines = p.ops.take(20).map { op ->
                            "L${op.lamport} • ${op.payload::class.simpleName}"
                        },
                    )
                }
                .onFailure { t ->
                    _state.value = ApplyChangesState(
                        loading = false,
                        url = url,
                        error = mapError(t),
                    )
                }
        }
    }

    fun apply() {
        if (_state.value.applying || _state.value.applied) return
        _state.value = _state.value.copy(applying = true, error = null)
        viewModelScope.launch {
            importer.apply(url)
                .onSuccess {
                    _state.value = _state.value.copy(applying = false, applied = true)
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(applying = false, error = mapError(t))
                }
        }
    }

    private fun mapError(t: Throwable): String = when ((t as? ImportException)?.error) {
        SneakernetImporter.ImportError.MalformedUrl -> "Lien malformé."
        SneakernetImporter.ImportError.MissingFields -> "Lien incomplet."
        SneakernetImporter.ImportError.SignatureMismatch ->
            "Signature invalide. Mauvaise clé ou lien altéré."
        SneakernetImporter.ImportError.EventNotJoined ->
            "Évènement inconnu sur cet appareil. Demande un lien " +
                "d'invitation (fairshare://join) à l'organisateur."
        is SneakernetImporter.ImportError.PayloadInvalid -> "Payload invalide."
        null -> t.message ?: "Erreur inconnue."
    }
}
