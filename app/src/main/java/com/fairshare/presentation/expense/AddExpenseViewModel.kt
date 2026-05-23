package com.fairshare.presentation.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.Participant
import com.fairshare.domain.model.SplitMode
import com.fairshare.domain.repository.ExpenseRepository
import com.fairshare.domain.repository.ParticipantRepository
import com.fairshare.domain.usecase.ComputeSharesUseCase
import com.fairshare.presentation.common.parseAmountToCents
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddExpenseState(
    val title: String = "",
    val amountText: String = "",
    val payerId: Long? = null,
    val mode: SplitMode = SplitMode.EQUAL,
    val selectedIds: Set<Long> = emptySet(),
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val participantRepository: ParticipantRepository,
    private val expenseRepository: ExpenseRepository,
    private val computeShares: ComputeSharesUseCase,
) : ViewModel() {

    private val eventId: Long = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])

    val participants: StateFlow<List<Participant>> =
        participantRepository.observeByEvent(eventId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(AddExpenseState())
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()

    fun setTitle(v: String) = _state.update { it.copy(title = v) }
    fun setAmount(v: String) = _state.update { it.copy(amountText = v) }
    fun setPayer(id: Long) = _state.update { it.copy(payerId = id) }
    fun setMode(m: SplitMode) = _state.update { it.copy(mode = m) }
    fun togglePayee(id: Long) = _state.update {
        it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id)
    }
    fun selectAll() = _state.update { it.copy(selectedIds = participants.value.map { p -> p.id }.toSet()) }

    fun save(onSuccess: () -> Unit) {
        val s = _state.value
        val amount = parseAmountToCents(s.amountText)
        val payer = s.payerId
        val payees = s.selectedIds.toList()
        when {
            s.title.isBlank() -> _state.update { it.copy(error = "Titre requis") }
            amount == null || amount <= 0 -> _state.update { it.copy(error = "Montant invalide") }
            payer == null -> _state.update { it.copy(error = "Choisis qui a payé") }
            payees.isEmpty() -> _state.update { it.copy(error = "Choisis au moins une personne concernée") }
            else -> viewModelScope.launch {
                _state.update { it.copy(isSaving = true, error = null) }
                val shares = computeShares(amount, payees, SplitMode.EQUAL)
                expenseRepository.add(
                    Expense(
                        eventId = eventId, title = s.title.trim(),
                        amountCents = amount, payerId = payer, shares = shares,
                    )
                )
                _state.update { it.copy(isSaving = false) }
                onSuccess()
            }
        }
    }
}
