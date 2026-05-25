package com.fairshare.presentation.eventsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.domain.model.Category
import com.fairshare.domain.repository.CategoryRepository
import com.fairshare.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-event preferences. Currently holds the custom-category CRUD; will
 * grow as more event-scoped settings appear (currency override, share
 * targets, …). Kept thin: all heavy logic lives in [CategoryRepository].
 */
@HiltViewModel
class EventSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    val eventId: String = checkNotNull(savedStateHandle[Route.ARG_EVENT_ID])

    val customCategories: StateFlow<List<Category>> =
        categoryRepository.observeByEvent(eventId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addCategory(name: String, emoji: String, color: Long) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            categoryRepository.upsert(
                Category(eventId = eventId, name = cleaned, emoji = emoji, color = color),
            )
        }
    }

    fun deleteCategory(id: String) {
        viewModelScope.launch { categoryRepository.delete(id) }
    }
}
