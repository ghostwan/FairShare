package com.fairshare.presentation.eventdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.sync.SyncCoordinator
import com.fairshare.data.sync.SyncWorker
import com.fairshare.domain.model.Balance
import com.fairshare.domain.model.Event
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.Participant
import com.fairshare.domain.model.Settlement
import com.fairshare.domain.repository.EventRepository
import com.fairshare.domain.repository.ExpenseRepository
import com.fairshare.domain.repository.ParticipantRepository
import com.fairshare.domain.usecase.ComputeBalancesUseCase
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
)

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
    private val participantRepository: ParticipantRepository,
    private val expenseRepository: ExpenseRepository,
    private val computeBalances: ComputeBalancesUseCase,
    private val syncCoordinator: SyncCoordinator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val eventId: String = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])

    val state: StateFlow<EventDetailState> = combine(
        eventRepository.observeEvent(eventId),
        participantRepository.observeByEvent(eventId),
        expenseRepository.observeByEvent(eventId),
    ) { event, participants, expenses ->
        val balances = computeBalances.balances(participants, expenses)
        val settlements = computeBalances.settlements(balances)
        EventDetailState(event, participants, expenses, balances, settlements)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailState())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        // Foreground sync on entry. Same fallback as EventsViewModel:
        // schedule a Worker retry on failure so transient network
        // hiccups don't lose us a pull.
        refresh()
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
}
