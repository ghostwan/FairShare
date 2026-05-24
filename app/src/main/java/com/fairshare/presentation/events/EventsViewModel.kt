package com.fairshare.presentation.events

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.sync.SyncCoordinator
import com.fairshare.data.sync.SyncWorker
import com.fairshare.domain.model.Event
import com.fairshare.domain.model.Participant
import com.fairshare.domain.repository.EventRepository
import com.fairshare.domain.repository.ParticipantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val participantRepository: ParticipantRepository,
    private val syncCoordinator: SyncCoordinator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val events: StateFlow<List<Event>> =
        eventRepository.observeEvents()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        // Best-effort foreground sync on first observation. A failure
        // here (offline, no events yet) is swallowed; the WorkManager
        // retry below keeps the network-bound case durable.
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val outcomes = syncCoordinator.syncAllEvents()
                if (outcomes.any { it.isFailure }) {
                    SyncWorker.enqueueOneShot(context)
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun createEvent(name: String, currency: String, participants: List<String>) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val eventId = eventRepository.create(Event(name = name.trim(), currency = currency))
            participants
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { p ->
                    participantRepository.add(Participant(eventId = eventId, name = p))
                }
            // Push the freshly-emitted ops to the Worker so other devices
            // see the new event without waiting for the next foreground.
            SyncWorker.enqueueOneShot(context, eventId)
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch { eventRepository.delete(id) }
    }
}
