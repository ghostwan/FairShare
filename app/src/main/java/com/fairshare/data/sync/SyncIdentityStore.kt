package com.fairshare.data.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fairshare.domain.model.sync.LamportClockLogic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore by preferencesDataStore(name = "sync_identity")

/**
 * Persistent device identity and Lamport clock for the sync engine.
 *
 * Stored in a dedicated DataStore (`sync_identity`) to keep its lifecycle
 * independent from user-facing settings.
 *
 * Contract:
 * - [deviceId] is generated on first read and never changes for the install.
 * - [tickLocal] returns a fresh strictly-monotonic value for emitting a new op.
 * - [observeRemote] catches the clock up to a received op's lamport value.
 *
 * All mutating calls are serialized through a [Mutex] so concurrent emitters
 * cannot produce the same lamport value.
 *
 * Reference: DESIGN.md §2.2 and §4.1.
 */
@Singleton
class SyncIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val deviceIdKey = stringPreferencesKey("device_id")
    private val lamportKey = longPreferencesKey("lamport_clock")
    private val mutex = Mutex()

    /** Returns the stable device id, generating one on first access. */
    suspend fun deviceId(): String = mutex.withLock {
        val prefs = context.syncDataStore.data.first()
        prefs[deviceIdKey] ?: run {
            val fresh = UUID.randomUUID().toString()
            context.syncDataStore.edit { it[deviceIdKey] = fresh }
            fresh
        }
    }

    /** Current Lamport value without advancing it. */
    suspend fun currentLamport(): Long =
        context.syncDataStore.data.first()[lamportKey] ?: 0L

    /**
     * Advance the clock and return the new value for stamping a freshly emitted
     * operation. Thread-safe under the internal mutex.
     */
    suspend fun tickLocal(): Long = mutex.withLock {
        val prefs = context.syncDataStore.data.first()
        val next = LamportClockLogic.tickLocal(prefs[lamportKey] ?: 0L)
        context.syncDataStore.edit { it[lamportKey] = next }
        next
    }

    /**
     * Reconcile the local clock against a received op's lamport value. Called
     * by the materializer for every inbound op. Returns the new local value.
     */
    suspend fun observeRemote(remote: Long): Long = mutex.withLock {
        val prefs = context.syncDataStore.data.first()
        val merged = LamportClockLogic.merge(prefs[lamportKey] ?: 0L, remote)
        context.syncDataStore.edit { it[lamportKey] = merged }
        merged
    }
}
