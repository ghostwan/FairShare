package com.fairshare.data.sync

import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.dao.ExpenseDao
import com.fairshare.data.local.dao.OperationDao
import com.fairshare.data.local.dao.ParticipantDao
import com.fairshare.data.local.entity.EventEntity
import com.fairshare.data.local.entity.ExpenseEntity
import com.fairshare.data.local.entity.ExpenseItemEntity
import com.fairshare.data.local.entity.ExpenseShareEntity
import com.fairshare.data.local.entity.OperationEntity
import com.fairshare.data.local.entity.ParticipantEntity
import com.fairshare.domain.model.sync.EntityKind
import com.fairshare.domain.model.sync.MaterializerLogic
import com.fairshare.domain.model.sync.OpOrigin
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import com.fairshare.domain.model.sync.entityId
import com.fairshare.domain.model.sync.entityKind
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single writer to the materialized tables in the CRDT pipeline
 * (DESIGN.md §4.3 and §5).
 *
 * Responsibilities:
 *
 * 1. Persist every incoming [Operation] to the append-only `operations`
 *    table (idempotent via the `op_id` primary key).
 * 2. Catch the local Lamport clock up to remote ops via
 *    [SyncIdentityStore.observeRemote].
 * 3. For every entity touched by the batch, re-resolve the LWW winner
 *    against the full event log and reflect it into the materialized
 *    tables (`events`, `participants`, `expenses`, `expense_shares`,
 *    `expense_items`, `expense_item_assignments`).
 * 4. Mark each op `applied = 1` once the materialized state reflects it.
 *
 * Determinism: replaying the log on a wiped database produces the same
 * materialized state. Idempotency: applying the same batch twice is a
 * no-op after the first run. Both invariants are covered by the
 * pure-logic property tests on [MaterializerLogic]; this class is a
 * thin orchestrator over Room DAOs.
 *
 * Locally-emitted ops also flow through this class so that the
 * materialization path is identical for LOCAL / SNEAKERNET / CLOUD ops
 * — there is only one code path that turns ops into UI-visible state.
 */
@Singleton
class OperationApplier @Inject constructor(
    private val operationDao: OperationDao,
    private val eventDao: EventDao,
    private val participantDao: ParticipantDao,
    private val expenseDao: ExpenseDao,
    private val syncIdentityStore: SyncIdentityStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    /**
     * Build a fresh [Operation] stamped with this device's identity and a
     * freshly-ticked Lamport value, then apply it locally. This is the
     * single entry point for repository writes in the CRDT pipeline
     * (DESIGN.md §5).
     *
     * The op is appended to the log with origin [OpOrigin.LOCAL] and the
     * materialized tables are updated synchronously before this call
     * returns, so callers can observe the new state immediately via the
     * existing Flow-based queries.
     */
    suspend fun applyLocal(eventId: String, payload: OpPayload): Operation {
        val op = Operation(
            opId = UUID.randomUUID().toString(),
            eventId = eventId,
            deviceId = syncIdentityStore.deviceId(),
            lamport = syncIdentityStore.tickLocal(),
            wallClockMs = System.currentTimeMillis(),
            payload = payload,
        )
        apply(listOf(op), OpOrigin.LOCAL)
        return op
    }

    /**
     * Apply a batch of operations. All ops in the batch must belong to
     * the same event; callers split per-event batches upstream.
     */
    suspend fun apply(ops: List<Operation>, origin: OpOrigin) {
        if (ops.isEmpty()) return
        val eventId = ops.first().eventId
        require(ops.all { it.eventId == eventId }) {
            "OperationApplier.apply: all ops must share the same eventId"
        }

        // 1. Catch the local Lamport clock up to remote ops before
        //    storing them; once they are in the log, every later emit
        //    must already be strictly greater.
        if (origin != OpOrigin.LOCAL) {
            for (op in ops) syncIdentityStore.observeRemote(op.lamport)
        }

        // 2. Append to the op log. OnConflict = IGNORE makes this safe
        //    against transport redeliveries.
        operationDao.insertAll(ops.map { it.toEntity(origin) })

        // 3. Re-resolve every entity touched by the batch against the
        //    full event log (Room is the source of truth — previously
        //    persisted ops may now lose to a newly arrived one with a
        //    higher lamport).
        //
        //    Order matters: PARTICIPANT and EXPENSE rows carry foreign
        //    keys to EVENT (and EXPENSE shares/items to PARTICIPANT), so
        //    a single JOIN batch containing the initial EventUpsert +
        //    ParticipantUpserts must materialize the event row first or
        //    Room throws a FOREIGN KEY constraint failure.
        val fullLog = operationDao.forEvent(eventId).mapNotNull { it.toDomainOrNull() }
        val touched = ops.map { it.payload.entityKind to it.payload.entityId }.toSet()
        val ordered = touched.sortedBy { (kind, _) ->
            when (kind) {
                EntityKind.EVENT -> 0
                EntityKind.PARTICIPANT -> 1
                EntityKind.EXPENSE -> 2
            }
        }
        for ((kind, id) in ordered) {
            materialize(eventId, kind, id, fullLog)
        }

        // 4. Mark applied. Done last so a crash mid-materialize leaves
        //    pending ops that will be retried on the next call.
        operationDao.markApplied(ops.map { it.opId })
    }

    private suspend fun materialize(
        eventId: String,
        kind: EntityKind,
        entityId: String,
        log: List<Operation>,
    ) {
        val winningPayload = MaterializerLogic.resolveEntity(kind, entityId, log)
        when (kind) {
            EntityKind.EVENT -> {
                if (winningPayload == null) {
                    eventDao.delete(entityId)
                } else {
                    val snap = (winningPayload as OpPayload.EventUpsert).event
                    // The encryption key is local-only and is never carried
                    // by ops (DESIGN.md §2.3). Preserve the existing key
                    // when an op updates the event's mutable fields; if the
                    // event has not yet been materialized on this device,
                    // leave it empty — the join flow (Step H follow-up)
                    // writes the key out-of-band from the invitation link.
                    val existingKey = eventDao.getById(snap.id)?.encryptionKey ?: ByteArray(0)
                    eventDao.insert(
                        EventEntity(
                            id = snap.id,
                            name = snap.name,
                            description = snap.description,
                            currency = snap.currency,
                            createdAt = snap.createdAt,
                            encryptionKey = existingKey,
                            archived = snap.archived,
                        ),
                    )
                }
            }

            EntityKind.PARTICIPANT -> {
                if (winningPayload == null) {
                    participantDao.delete(entityId)
                } else {
                    val snap = (winningPayload as OpPayload.ParticipantUpsert).participant
                    // The parent event may have lost LWW to a local EventDelete
                    // tombstone with a higher lamport (e.g., the user deleted
                    // the event locally and is now re-importing seed ops).
                    // Without this guard, Room throws FOREIGN KEY failed.
                    if (eventDao.getById(snap.eventId) == null) return
                    participantDao.insert(
                        ParticipantEntity(
                            id = snap.id,
                            eventId = snap.eventId,
                            name = snap.name,
                        ),
                    )
                }
            }

            EntityKind.EXPENSE -> {
                if (winningPayload == null) {
                    expenseDao.delete(entityId)
                } else {
                    val snap = (winningPayload as OpPayload.ExpenseUpsert).expense
                    // Same parent-absence guard as PARTICIPANT above.
                    if (eventDao.getById(snap.eventId) == null) return
                    val entity = ExpenseEntity(
                        id = snap.id,
                        eventId = snap.eventId,
                        title = snap.title,
                        amountCents = snap.amountCents,
                        payerId = snap.payerId,
                        date = snap.date,
                        isSettlement = snap.isSettlement,
                    )
                    val shareEntities = snap.shares.map {
                        ExpenseShareEntity(
                            expenseId = snap.id,
                            participantId = it.participantId,
                            amountCents = it.amountCents,
                        )
                    }
                    val itemPairs = snap.items.mapIndexed { idx, item ->
                        ExpenseItemEntity(
                            id = item.id,
                            expenseId = snap.id,
                            label = item.label,
                            priceCents = item.priceCents,
                            quantity = item.quantity,
                            position = idx,
                        ) to item.assignedTo.toList()
                    }
                    expenseDao.upsertWithDetails(entity, shareEntities, itemPairs)
                }
            }
        }
    }

    private fun Operation.toEntity(origin: OpOrigin): OperationEntity =
        OperationEntity(
            opId = opId,
            eventId = eventId,
            deviceId = deviceId,
            lamport = lamport,
            wallClockMs = wallClockMs,
            payloadJson = json.encodeToString(OpPayload.serializer(), payload),
            applied = false,
            origin = origin.name,
        )

    private fun OperationEntity.toDomainOrNull(): Operation? = runCatching {
        Operation(
            opId = opId,
            eventId = eventId,
            deviceId = deviceId,
            lamport = lamport,
            wallClockMs = wallClockMs,
            payload = json.decodeFromString(OpPayload.serializer(), payloadJson),
        )
    }.getOrNull()
}
