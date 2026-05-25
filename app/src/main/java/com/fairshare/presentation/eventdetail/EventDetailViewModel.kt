package com.fairshare.presentation.eventdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.sync.SyncCoordinator
import com.fairshare.data.sync.SyncWorker
import com.fairshare.domain.model.Balance
import com.fairshare.domain.model.Category
import com.fairshare.domain.model.Event
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.ExpenseShare
import com.fairshare.domain.model.Participant
import com.fairshare.domain.model.Settlement
import com.fairshare.domain.repository.CategoryRepository
import com.fairshare.domain.repository.EventRepository
import com.fairshare.domain.repository.ExpenseRepository
import com.fairshare.domain.repository.ParticipantRepository
import com.fairshare.domain.usecase.ComputeBalancesUseCase
import com.fairshare.domain.usecase.CategoryStat
import com.fairshare.domain.usecase.ComputeCategoryStatsUseCase
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EventDetailState(
    val event: Event? = null,
    val participants: List<Participant> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val balances: List<Balance> = emptyList(),
    val settlements: List<Settlement> = emptyList(),
    /**
     * Custom categories of this event. Combined with
     * [com.fairshare.domain.model.DefaultCategories.ALL] at render time
     * to resolve an [Expense.categoryId] to a displayable [Category].
     */
    val customCategories: List<Category> = emptyList(),
    /**
     * Per-category aggregation of [expenses] (settlements excluded),
     * pre-computed by [ComputeCategoryStatsUseCase] so the Stats tab
     * stays a thin renderer.
     */
    val categoryStats: List<CategoryStat> = emptyList(),
    /**
     * `true` after the first emission of the upstream `combine`. Lets
     * the screen tell "still loading" from "loaded with empty data" so
     * empty-state placeholders don't flash during the cold start.
     */
    val loaded: Boolean = false,
)

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
    private val participantRepository: ParticipantRepository,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val computeBalances: ComputeBalancesUseCase,
    private val computeCategoryStats: ComputeCategoryStatsUseCase,
    private val syncCoordinator: SyncCoordinator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val eventId: String = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])

    val state: StateFlow<EventDetailState> = combine(
        eventRepository.observeEvent(eventId),
        participantRepository.observeByEvent(eventId),
        expenseRepository.observeByEvent(eventId),
        categoryRepository.observeByEvent(eventId),
    ) { event, participants, expenses, customCategories ->
        val balances = computeBalances.balances(participants, expenses)
        val settlements = computeBalances.settlements(balances)
        val categoryStats = computeCategoryStats(expenses, customCategories)
        EventDetailState(
            event = event,
            participants = participants,
            expenses = expenses,
            balances = balances,
            settlements = settlements,
            customCategories = customCategories,
            categoryStats = categoryStats,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailState())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Called from the screen ON_RESUME. Performs a single silent
     * pull (no spinner) so any push notification missed while the
     * screen was off is caught up. Real-time updates otherwise arrive
     * via FCM (see `FairShareMessagingService`); the pull-to-refresh
     * spinner is reserved for explicit user gestures.
     */
    fun onResume() {
        viewModelScope.launch { silentRefresh() }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val result = syncCoordinator.syncEvent(eventId)
                if (result.isFailure) {
                    SyncWorker.enqueueOneShot(context, eventId)
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun silentRefresh() {
        val result = syncCoordinator.syncEvent(eventId)
        if (result.isFailure) {
            SyncWorker.enqueueOneShot(context, eventId)
        }
    }

    /**
     * Renames the current event by emitting an EventUpsert op (LWW
     * snapshot) so all paired devices converge on the new name on
     * their next pull.
     */
    fun renameEvent(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val current = state.value.event ?: return@launch
            if (current.name == trimmed) return@launch
            eventRepository.update(current.copy(name = trimmed))
            SyncWorker.enqueueOneShot(context, eventId)
        }
    }

    fun addParticipant(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            participantRepository.add(Participant(eventId = eventId, name = name.trim()))
            SyncWorker.enqueueOneShot(context, eventId)
        }
    }

    fun removeParticipant(id: String) = viewModelScope.launch {
        participantRepository.delete(id)
        SyncWorker.enqueueOneShot(context, eventId)
    }

    fun removeExpense(id: String) = viewModelScope.launch {
        expenseRepository.delete(id)
        SyncWorker.enqueueOneShot(context, eventId)
    }

    /**
     * Records a [Settlement] suggestion as a real expense so the balances
     * recompute to zero on its participants. Stored as a plain expense
     * with payer = debtor and a single share on the creditor (same total
     * amount), tagged `isSettlement = true` so the timeline can render it
     * differently. After the write the suggestion list disappears
     * naturally on the next emission of [computeBalances].
     */
    fun recordSettlement(settlement: Settlement) = viewModelScope.launch {
        val title = "Remboursement ${settlement.fromName} → ${settlement.toName}"
        expenseRepository.add(
            Expense(
                eventId = eventId,
                title = title,
                amountCents = settlement.amountCents,
                payerId = settlement.fromId,
                shares = listOf(
                    ExpenseShare(
                        participantId = settlement.toId,
                        amountCents = settlement.amountCents,
                    )
                ),
                isSettlement = true,
            )
        )
        SyncWorker.enqueueOneShot(context, eventId)
    }
}
