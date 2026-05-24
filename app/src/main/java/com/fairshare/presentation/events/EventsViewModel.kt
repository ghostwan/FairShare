package com.fairshare.presentation.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.domain.model.Event
import com.fairshare.domain.model.Participant
import com.fairshare.domain.repository.EventRepository
import com.fairshare.domain.repository.ParticipantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val participantRepository: ParticipantRepository,
) : ViewModel() {

    val events: StateFlow<List<Event>> =
        eventRepository.observeEvents()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch { eventRepository.delete(id) }
    }
}
