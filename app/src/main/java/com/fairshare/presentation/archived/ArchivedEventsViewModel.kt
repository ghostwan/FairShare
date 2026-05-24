package com.fairshare.presentation.archived

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.sync.SyncWorker
import com.fairshare.domain.model.Event
import com.fairshare.domain.repository.EventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Read-only listing of archived events plus actions to unarchive or
 * delete them. Archive is a per-event LWW field so flipping it back to
 * `false` re-surfaces the event on every synced device.
 *
 * Compaction (snapshot + Worker-side op purge) is Phase C+D and lives
 * separately; this view model only handles the active/archived split.
 */
@HiltViewModel
class ArchivedEventsViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val events: StateFlow<List<Event>> =
        eventRepository.observeArchivedEvents()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unarchive(id: String) {
        viewModelScope.launch {
            val current = eventRepository.observeEvent(id).first() ?: return@launch
            if (!current.archived) return@launch
            eventRepository.update(current.copy(archived = false))
            SyncWorker.enqueueOneShot(context, id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { eventRepository.delete(id) }
    }
}
