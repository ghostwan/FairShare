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

    fun setExpandQuantities(value: Boolean) {
        viewModelScope.launch { settings.setExpandQuantities(value) }
    }
}
