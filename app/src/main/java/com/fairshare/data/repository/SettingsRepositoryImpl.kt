package com.fairshare.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
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

    override val expandQuantities: Flow<Boolean> =
        context.settingsDataStore.data.map { it[expandKey] ?: true }

    override suspend fun setExpandQuantities(value: Boolean) {
        context.settingsDataStore.edit { it[expandKey] = value }
    }
}
