package com.fairshare.data.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.cloudCursorDataStore by preferencesDataStore(name = "cloud_cursors")

/**
 * Persistent per-event push cursor for [WorkerCloudTransport].
 *
 * Why a separate cursor for push: the pull cursor can be derived from
 * the op log itself (`MAX(lamport) WHERE origin = CLOUD`), but the
 * push cursor cannot — locally-emitted ops carry origin LOCAL even
 * after they've been pushed, and re-deriving from a "pushed-at"
 * boolean column would require a schema migration we don't need yet.
 *
 * Stored as a flat `cloud_push_cursor_<eventId>` Long key, defaulting
 * to 0. Callers update it monotonically after a successful push to
 * avoid re-sending the same ops on the next sync.
 *
 * This DataStore is intentionally separate from
 * [SyncIdentityStore]'s `sync_identity` and from
 * `SettingsRepositoryImpl.settings`: cursors are per-event throwaway
 * state, not identity or user-facing preferences.
 */
@Singleton
class CloudCursorStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun keyFor(eventId: String) =
        longPreferencesKey("cloud_push_cursor_$eventId")

    suspend fun pushCursor(eventId: String): Long =
        context.cloudCursorDataStore.data.first()[keyFor(eventId)] ?: 0L

    /**
     * Advances the cursor to `max(current, value)`. Guards against an
     * out-of-order coroutine that would otherwise rewind the cursor
     * and cause duplicate pushes.
     */
    suspend fun advancePushCursor(eventId: String, value: Long) {
        val key = keyFor(eventId)
        context.cloudCursorDataStore.edit { prefs ->
            val current = prefs[key] ?: 0L
            if (value > current) prefs[key] = value
        }
    }

    /** For "remove from this device" flows: drop cursor state. */
    suspend fun clear(eventId: String) {
        context.cloudCursorDataStore.edit { it.remove(keyFor(eventId)) }
    }
}
