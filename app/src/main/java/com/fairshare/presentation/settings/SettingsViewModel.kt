package com.fairshare.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.data.sync.SyncCoordinator
import com.fairshare.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Last-attempt sync status held in-memory by [SettingsViewModel]. Not
 * persisted: resetting on app restart is acceptable for a debug-style
 * surface and avoids a schema migration. Promote to DataStore the day
 * we want a long-term history.
 */
data class CloudSyncStatus(
    val isRunning: Boolean = false,
    val lastAttemptMs: Long? = null,
    val lastSuccessCount: Int = 0,
    val lastFailureCount: Int = 0,
    val lastError: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val syncCoordinator: SyncCoordinator,
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

    val cloudBaseUrl: StateFlow<String> =
        settings.cloudBaseUrl
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                SettingsRepository.DEFAULT_CLOUD_BASE_URL,
            )

    val autoRefreshEnabled: StateFlow<Boolean> =
        settings.autoRefreshEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Convenience for the "Reset" button. */
    val defaultCloudBaseUrl: String = SettingsRepository.DEFAULT_CLOUD_BASE_URL

    private val _syncStatus = MutableStateFlow(CloudSyncStatus())
    val syncStatus: StateFlow<CloudSyncStatus> = _syncStatus.asStateFlow()

    fun setExpandQuantities(value: Boolean) {
        viewModelScope.launch { settings.setExpandQuantities(value) }
    }

    fun setGeminiApiKey(value: String) {
        viewModelScope.launch { settings.setGeminiApiKey(value) }
    }

    fun setGeminiModel(value: String) {
        viewModelScope.launch { settings.setGeminiModel(value) }
    }

    fun setCloudBaseUrl(value: String) {
        viewModelScope.launch { settings.setCloudBaseUrl(value) }
    }

    fun setAutoRefreshEnabled(value: Boolean) {
        viewModelScope.launch { settings.setAutoRefreshEnabled(value) }
    }

    fun resetCloudBaseUrl() = setCloudBaseUrl(defaultCloudBaseUrl)

    /**
     * Forces a synchronous sync cycle across every event with an
     * encryption key. Captures the outcome in [syncStatus] so the UI
     * can show success / failure without parsing logcat.
     */
    fun syncNow() {
        if (_syncStatus.value.isRunning) return
        viewModelScope.launch {
            _syncStatus.value = _syncStatus.value.copy(isRunning = true, lastError = null)
            val outcomes = runCatching { syncCoordinator.syncAllEvents() }
            val now = System.currentTimeMillis()
            outcomes.fold(
                onSuccess = { list ->
                    _syncStatus.value = CloudSyncStatus(
                        isRunning = false,
                        lastAttemptMs = now,
                        lastSuccessCount = list.count { it.isSuccess },
                        lastFailureCount = list.count { it.isFailure },
                        lastError = list.firstNotNullOfOrNull { it.exceptionOrNull()?.message },
                    )
                },
                onFailure = { t ->
                    _syncStatus.value = CloudSyncStatus(
                        isRunning = false,
                        lastAttemptMs = now,
                        lastFailureCount = 1,
                        lastError = t.message ?: t::class.simpleName,
                    )
                },
            )
        }
    }
}
