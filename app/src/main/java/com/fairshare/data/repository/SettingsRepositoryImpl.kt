package com.fairshare.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fairshare.BuildConfig
import com.fairshare.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private val expandKey = booleanPreferencesKey("expand_quantities")
    private val geminiApiKeyKey = stringPreferencesKey("gemini_api_key")
    private val geminiModelKey = stringPreferencesKey("gemini_model")
    private val cloudBaseUrlKey = stringPreferencesKey("cloud_base_url")
    private val autoRefreshKey = booleanPreferencesKey("auto_refresh_enabled")

    override val expandQuantities: Flow<Boolean> =
        context.settingsDataStore.data.map { it[expandKey] ?: true }

    override suspend fun setExpandQuantities(value: Boolean) {
        context.settingsDataStore.edit { it[expandKey] = value }
    }

    override val geminiApiKey: Flow<String> =
        context.settingsDataStore.data.map { it[geminiApiKeyKey] ?: BuildConfig.GEMINI_API_KEY }

    override suspend fun setGeminiApiKey(value: String) {
        context.settingsDataStore.edit { it[geminiApiKeyKey] = value }
    }

    override val geminiModel: Flow<String> =
        context.settingsDataStore.data.map { it[geminiModelKey] ?: BuildConfig.GEMINI_MODEL }

    override suspend fun setGeminiModel(value: String) {
        context.settingsDataStore.edit { it[geminiModelKey] = value }
    }

    override val cloudBaseUrl: Flow<String> =
        context.settingsDataStore.data.map { it[cloudBaseUrlKey] ?: SettingsRepository.DEFAULT_CLOUD_BASE_URL }

    override suspend fun setCloudBaseUrl(value: String) {
        context.settingsDataStore.edit { it[cloudBaseUrlKey] = value.trimEnd('/') }
    }

    override val autoRefreshEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[autoRefreshKey] ?: true }

    override suspend fun setAutoRefreshEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[autoRefreshKey] = value }
    }
}
