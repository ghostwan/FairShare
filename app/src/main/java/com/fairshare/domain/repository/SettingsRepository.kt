package com.fairshare.domain.repository

import kotlinx.coroutines.flow.Flow

/** Persistent user preferences. */
interface SettingsRepository {
    /**
     * When `true`, a line like "2 x Bière 11,00" is split into 2 separate items at unit
     * price so each unit can be assigned to a different person.
     * When `false`, the line is kept as one "2x Bière 11,00" item that can still be
     * shared between several people equally.
     */
    val expandQuantities: Flow<Boolean>
    suspend fun setExpandQuantities(value: Boolean)
}
