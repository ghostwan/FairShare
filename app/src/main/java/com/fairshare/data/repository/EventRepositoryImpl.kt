package com.fairshare.data.repository

import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.dao.OperationDao
import com.fairshare.data.local.entity.EventEntity
import com.fairshare.data.sync.OperationApplier
import com.fairshare.data.sync.PushTokenRegistrar
import com.fairshare.domain.model.Event
import com.fairshare.domain.model.sync.EventSnapshot
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.util.Log
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject

/**
 * Reads go directly to Room (cheap, no Lamport / op-log involvement
 * needed). Writes are funneled through [OperationApplier.applyLocal]
 * which:
 *
 * 1. Stamps the change with this device's id + a fresh Lamport tick.
 * 2. Appends the op to the append-only log.
 * 3. Re-materializes the affected entity via LWW.
 *
 * Special case [create]: a fresh 32-byte encryption key is generated
 * client-side (DESIGN.md §2.3) and persisted directly on the
 * [EventEntity] before the EventUpsert op is emitted. The key is
 * intentionally NOT part of the op payload — it must never leave the
 * device through the op log, only via the out-of-band invitation URL.
 * The materializer preserves the existing key when applying the
 * subsequent op, so this two-step is correct.
 *
 * Reference: DESIGN.md §5.
 */
class EventRepositoryImpl @Inject constructor(
    private val dao: EventDao,
    private val operationDao: OperationDao,
    private val applier: OperationApplier,
    private val pushRegistrar: PushTokenRegistrar,
) : EventRepository {

    private val secureRandom = SecureRandom()

    override fun observeEvents(): Flow<List<Event>> =
        dao.observeActive()
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()

    override fun observeArchivedEvents(): Flow<List<Event>> =
        dao.observeArchived()
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()

    override fun observeEvent(id: String): Flow<Event?> =
        dao.observeById(id)
            .map { it?.toDomain() }
            .distinctUntilChanged()

    override suspend fun create(event: Event): String {
        val id = event.id.ifBlank { UUID.randomUUID().toString() }
        val key = ByteArray(KEY_LENGTH).also(secureRandom::nextBytes)
        // Persist the key out-of-band before the materializer reads it.
        dao.insert(
            EventEntity(
                id = id,
                name = event.name,
                description = event.description,
                currency = event.currency,
                createdAt = event.createdAt,
                encryptionKey = key,
                archived = event.archived,
            ),
        )
        applier.applyLocal(
            eventId = id,
            payload = OpPayload.EventUpsert(event.copy(id = id).toSnapshot()),
        )
        // Register for FCM pushes on the freshly created event so
        // peers that subsequently join via invitation also push to us.
        // Failure is non-fatal: the polling fallback (or the next
        // app startup re-registration) will catch up.
        pushRegistrar.register(id).onFailure {
            Log.w("EventRepository", "FCM register failed for $id: ${it.message}")
        }
        return id
    }

    override suspend fun update(event: Event) {
        applier.applyLocal(
            eventId = event.id,
            payload = OpPayload.EventUpsert(event.toSnapshot()),
        )
    }

    override suspend fun delete(id: String) {
        // Unregister our FCM token first so the Worker stops fanning
        // out pushes to a device that no longer cares. Best-effort:
        // failure leaves a stale token that the Worker will prune on
        // its next UNREGISTERED FCM response.
        pushRegistrar.unregister(id).onFailure {
            Log.w("EventRepository", "FCM unregister failed for $id: ${it.message}")
        }
        // Local-only "remove from this device": we intentionally do NOT
        // emit an EventDelete op. Emitting a tombstone would mean that
        // re-importing the same event from a peer would silently lose to
        // the local delete via LWW (the tombstone's lamport is higher),
        // which is the opposite of what the user expects when they remove
        // a copy from a single device and want a peer to be able to
        // re-share it.
        //
        // We hard-purge both the materialized rows (via FK CASCADE) and
        // the op log for this event so a subsequent JOIN re-materializes
        // cleanly.
        operationDao.deleteAllForEvent(id)
        dao.delete(id)
    }

    companion object {
        private const val KEY_LENGTH = 32
    }
}

private fun EventEntity.toDomain() = Event(id, name, description, currency, createdAt, archived)
private fun Event.toSnapshot() = EventSnapshot(
    id = id,
    name = name,
    description = description,
    currency = currency,
    createdAt = createdAt,
    archived = archived,
)
