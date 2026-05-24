package com.fairshare.presentation.expense

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.sync.SyncWorker
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.Participant
import com.fairshare.domain.model.SplitMode
import com.fairshare.domain.repository.ExpenseRepository
import com.fairshare.domain.repository.ParticipantRepository
import com.fairshare.domain.usecase.ComputeSharesUseCase
import com.fairshare.presentation.common.parseAmountToCents
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val payerId: String? = null,
    val mode: SplitMode = SplitMode.EQUAL,
    val selectedIds: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val isEditMode: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val participantRepository: ParticipantRepository,
    private val expenseRepository: ExpenseRepository,
    private val computeShares: ComputeSharesUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val eventId: String = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])
    private val editingExpenseId: String? = savedStateHandle.get<String>(Route.ARG_EXPENSE_ID)

    val participants: StateFlow<List<Participant>> =
        participantRepository.observeByEvent(eventId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(AddExpenseState(isEditMode = editingExpenseId != null))
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()

    init {
        editingExpenseId?.let { id ->
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true) }
                val expense = expenseRepository.get(id)
                if (expense != null) {
                    _state.update {
                        it.copy(
                            title = expense.title,
                            amountText = String.format("%.2f", expense.amountCents / 100.0),
                            payerId = expense.payerId,
                            selectedIds = expense.shares.map { s -> s.participantId }.toSet(),
                            isLoading = false,
                        )
                    }
                } else {
                    _state.update { it.copy(isLoading = false, error = "Dépense introuvable") }
                }
            }
        }
    }

    fun setTitle(v: String) = _state.update { it.copy(title = v) }
    fun setAmount(v: String) = _state.update { it.copy(amountText = v) }
    fun setPayer(id: String) = _state.update { it.copy(payerId = id) }
    fun setMode(m: SplitMode) = _state.update { it.copy(mode = m) }
    fun togglePayee(id: String) = _state.update {
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
                val expense = Expense(
                    id = editingExpenseId ?: "",
                    eventId = eventId, title = s.title.trim(),
                    amountCents = amount, payerId = payer, shares = shares,
                )
                if (editingExpenseId != null) expenseRepository.update(expense)
                else expenseRepository.add(expense)
                // Propagate the newly-emitted op without waiting for
                // the next ON_RESUME of the events list.
                SyncWorker.enqueueOneShot(context, eventId)
                _state.update { it.copy(isSaving = false) }
                onSuccess()
            }
        }
    }

    fun delete(onSuccess: () -> Unit) {
        val id = editingExpenseId ?: return
        viewModelScope.launch {
            expenseRepository.delete(id)
            SyncWorker.enqueueOneShot(context, eventId)
            onSuccess()
        }
    }
}
