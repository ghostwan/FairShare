package com.fairshare.presentation.receipt

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.di.Gemini
import com.fairshare.di.MlKit
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.ExpenseItem
import com.fairshare.domain.model.Participant
import com.fairshare.domain.model.ReceiptItem
import com.fairshare.domain.repository.ExpenseRepository
import com.fairshare.domain.repository.ParticipantRepository
import com.fairshare.domain.repository.ReceiptParser
import com.fairshare.domain.repository.SettingsRepository
import com.fairshare.domain.usecase.AssignReceiptItemsUseCase
import com.fairshare.domain.usecase.ExpandReceiptQuantitiesUseCase
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ScanReceiptState(
    val title: String = "Ticket de caisse",
    val payerId: String? = null,
    val items: List<ReceiptItem> = emptyList(),
    val isScanning: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    /**
     * URI of the last image successfully sent to a parser. Kept around so the user
     * can re-parse the same image with the Gemini fallback if ML Kit's output
     * was poor — without re-taking the photo.
     */
    val lastImageUri: Uri? = null,
)

@HiltViewModel
class ScanReceiptViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    participantRepository: ParticipantRepository,
    @MlKit private val mlKitParser: ReceiptParser,
    @Gemini private val geminiParser: ReceiptParser,
    private val expenseRepository: ExpenseRepository,
    private val assignReceiptItems: AssignReceiptItemsUseCase,
    private val expandReceiptQuantities: ExpandReceiptQuantitiesUseCase,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val eventId: String = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])

    val participants: StateFlow<List<Participant>> =
        participantRepository.observeByEvent(eventId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val expandQuantities: StateFlow<Boolean> =
        settings.expandQuantities
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Exposed so the UI can disable the "Réessayer avec IA" action when no key is
     * configured (BuildConfig default empty + no user override in Settings).
     */
    val hasGeminiKey: StateFlow<Boolean> =
        settings.geminiApiKey.map { it.isNotBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(ScanReceiptState())
    val state: StateFlow<ScanReceiptState> = _state.asStateFlow()

    fun setTitle(v: String) = _state.update { it.copy(title = v) }
    fun setPayer(id: String) = _state.update { it.copy(payerId = id) }

    fun scan(uri: Uri) = runParser(uri, mlKitParser)

    /**
     * Re-runs the parsing pipeline on the last scanned image using the Gemini AI
     * fallback. Existing items are *replaced* by Gemini's output. No-op if no
     * image was scanned yet or no API key is configured.
     */
    fun reparseWithGemini() {
        val uri = state.value.lastImageUri ?: run {
            _state.update { it.copy(error = "Aucune image à ré-analyser") }
            return
        }
        runParser(uri, geminiParser)
    }

    private fun runParser(uri: Uri, parser: ReceiptParser) {
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, error = null, lastImageUri = uri) }
            try {
                val parsed = parser.parse(uri)
                // Read the current setting *now* — cannot rely on the cold StateFlow's
                // initial value because nothing in this VM stays subscribed to it.
                val expand = settings.expandQuantities.first()
                val expanded = expandReceiptQuantities(parsed, expand)
                _state.update { it.copy(items = expanded, isScanning = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isScanning = false, error = e.message ?: "Erreur OCR") }
            }
        }
    }

    fun toggleAssignment(itemId: String, participantId: String) = _state.update { s ->
        s.copy(items = s.items.map {
            if (it.id != itemId) it else it.copy(
                assignedTo = if (participantId in it.assignedTo) it.assignedTo - participantId
                else it.assignedTo + participantId
            )
        })
    }

    fun updateItem(itemId: String, label: String, priceCents: Long) = _state.update { s ->
        s.copy(items = s.items.map {
            if (it.id != itemId) it else it.copy(label = label, priceCents = priceCents)
        })
    }

    fun deleteItem(itemId: String) = _state.update { s ->
        s.copy(items = s.items.filterNot { it.id == itemId })
    }

    fun addItem() = _state.update { s ->
        s.copy(items = s.items + ReceiptItem(UUID.randomUUID().toString(), "", 0L, quantity = 1))
    }

    fun totalCents(): Long = state.value.items.sumOf { it.priceCents }

    fun save(onSuccess: () -> Unit) {
        val s = state.value
        val payer = s.payerId
        when {
            payer == null -> _state.update { it.copy(error = "Choisis qui a payé") }
            s.items.isEmpty() -> _state.update { it.copy(error = "Aucun article") }
            else -> viewModelScope.launch {
                _state.update { it.copy(isSaving = true, error = null) }
                val allIds = participants.value.map { it.id }
                val shares = assignReceiptItems(s.items, allIds)
                val total = totalCents()
                val itemDetails = s.items.map { ri ->
                    ExpenseItem(
                        label = ri.label,
                        priceCents = ri.priceCents,
                        quantity = ri.quantity,
                        assignedTo = ri.assignedTo,
                    )
                }
                expenseRepository.add(
                    Expense(
                        eventId = eventId, title = s.title.ifBlank { "Ticket de caisse" },
                        amountCents = total, payerId = payer, shares = shares,
                        items = itemDetails,
                    )
                )
                _state.update { it.copy(isSaving = false) }
                onSuccess()
            }
        }
    }
}
