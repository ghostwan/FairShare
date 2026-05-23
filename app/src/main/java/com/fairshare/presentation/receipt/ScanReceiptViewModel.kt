package com.fairshare.presentation.receipt

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.Participant
import com.fairshare.domain.model.ReceiptItem
import com.fairshare.domain.repository.ExpenseRepository
import com.fairshare.domain.repository.ParticipantRepository
import com.fairshare.domain.repository.ReceiptParser
import com.fairshare.domain.usecase.AssignReceiptItemsUseCase
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ScanReceiptState(
    val title: String = "Ticket de caisse",
    val payerId: Long? = null,
    val items: List<ReceiptItem> = emptyList(),
    val isScanning: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ScanReceiptViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    participantRepository: ParticipantRepository,
    private val parser: ReceiptParser,
    private val expenseRepository: ExpenseRepository,
    private val assignReceiptItems: AssignReceiptItemsUseCase,
) : ViewModel() {

    private val eventId: Long = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])

    val participants: StateFlow<List<Participant>> =
        participantRepository.observeByEvent(eventId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(ScanReceiptState())
    val state: StateFlow<ScanReceiptState> = _state.asStateFlow()

    fun setTitle(v: String) = _state.update { it.copy(title = v) }
    fun setPayer(id: Long) = _state.update { it.copy(payerId = id) }

    fun scan(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, error = null) }
            try {
                val items = parser.parse(uri)
                _state.update { it.copy(items = items, isScanning = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isScanning = false, error = e.message ?: "Erreur OCR") }
            }
        }
    }

    fun toggleAssignment(itemId: String, participantId: Long) = _state.update { s ->
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
        s.copy(items = s.items + ReceiptItem(UUID.randomUUID().toString(), "", 0L))
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
                expenseRepository.add(
                    Expense(
                        eventId = eventId, title = s.title.ifBlank { "Ticket de caisse" },
                        amountCents = total, payerId = payer, shares = shares,
                    )
                )
                _state.update { it.copy(isSaving = false) }
                onSuccess()
            }
        }
    }
}
