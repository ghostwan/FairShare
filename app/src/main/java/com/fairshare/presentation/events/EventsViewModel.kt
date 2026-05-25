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
import kotlinx.coroutines.flow.first
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

    /**
     * Nullable initial value so the screen can distinguish "still
     * loading" (events == null) from "loaded but empty" (events ==
     * emptyList). Prevents the empty-state placeholder from flashing
     * during the brief window before the first Room emission.
     */
    val events: StateFlow<List<Event>?> =
        eventRepository.observeEvents()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Called from the screen's ON_RESUME. Performs a single silent
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
                val outcomes = syncCoordinator.syncAllEvents()
                if (outcomes.any { it.isFailure }) {
                    SyncWorker.enqueueOneShot(context)
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun silentRefresh() {
        val outcomes = syncCoordinator.syncAllEvents()
        if (outcomes.any { it.isFailure }) {
            SyncWorker.enqueueOneShot(context)
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

    /**
     * Toggle the archive flag. The change is an `EventUpsert` op that
     * propagates LWW-style through the standard sync pipeline, so all
     * devices see the same archived state once they pull. Hidden from
     * the main list locally as soon as the op is applied.
     */
    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch {
            val current = eventRepository.observeEvent(id).first() ?: return@launch
            if (current.archived == archived) return@launch
            eventRepository.update(current.copy(archived = archived))
            SyncWorker.enqueueOneShot(context, id)
        }
    }
}
