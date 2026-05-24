package com.fairshare.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val expandQuantities: StateFlow<Boolean> =
        settings.expandQuantities
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val geminiApiKey: StateFlow<String> =
        settings.geminiApiKey
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val geminiModel: StateFlow<String> =
        settings.geminiModel
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "gemini-2.5-flash")

    fun setExpandQuantities(value: Boolean) {
        viewModelScope.launch { settings.setExpandQuantities(value) }
    }

    fun setGeminiApiKey(value: String) {
        viewModelScope.launch { settings.setGeminiApiKey(value) }
    }

    fun setGeminiModel(value: String) {
        viewModelScope.launch { settings.setGeminiModel(value) }
    }
}
