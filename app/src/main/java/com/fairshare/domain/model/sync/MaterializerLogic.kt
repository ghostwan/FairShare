package com.fairshare.domain.model.sync

/**
 * Pure CRDT materialization logic.
 *
 * Given an unordered set of [Operation]s scoped to a single event, it
 * computes the surviving state of every entity by applying LWW per
 * `(entityKind, entityId)` family (DESIGN.md §4.2):
 *
 * 1. Group ops by `(entityKind, entityId)`.
 * 2. Pick the op with the highest `(lamport, deviceId)` pair via
 *    [Operation.LwwOrder].
 * 3. If the winner is an Upsert, the snapshot is the new state. If the
 *    winner is a Delete, the entity is absent from the result.
 *
 * The function is deterministic and order-independent, which is exactly
 * the property needed for convergence: two devices fed the same set of
 * ops in any order produce the same [MaterializedState].
 *
 * Performance: O(n log n) by sort per group; n is the op count for one
 * event. Acceptable because the materializer only runs incrementally on
 * the entities touched by an incoming batch.
 */
object MaterializerLogic {

    fun resolve(ops: Collection<Operation>): MaterializedState {
        val events = mutableMapOf<String, EventSnapshot>()
        val participants = mutableMapOf<String, ParticipantSnapshot>()
        val expenses = mutableMapOf<String, ExpenseSnapshot>()

        ops.groupBy { it.payload.entityKind to it.payload.entityId }
            .forEach { (key, group) ->
                val winner = group.maxWithOrNull(Operation.LwwOrder) ?: return@forEach
                val (kind, id) = key
                when (kind) {
                    EntityKind.EVENT -> when (val p = winner.payload) {
                        is OpPayload.EventUpsert -> events[id] = p.event
                        is OpPayload.EventDelete -> { /* tombstone: omit */ }
                        else -> error("unexpected payload $p for EVENT")
                    }
                    EntityKind.PARTICIPANT -> when (val p = winner.payload) {
                        is OpPayload.ParticipantUpsert -> participants[id] = p.participant
                        is OpPayload.ParticipantDelete -> { /* tombstone */ }
                        else -> error("unexpected payload $p for PARTICIPANT")
                    }
                    EntityKind.EXPENSE -> when (val p = winner.payload) {
                        is OpPayload.ExpenseUpsert -> expenses[id] = p.expense
                        is OpPayload.ExpenseDelete -> { /* tombstone */ }
                        else -> error("unexpected payload $p for EXPENSE")
                    }
                }
            }

        return MaterializedState(events, participants, expenses)
    }

    /**
     * Single-entity variant. Returns the winning [OpPayload], or `null`
     * if the entity has no ops or its current winner is a tombstone.
     *
     * Used by the persistent materializer to update only the entities
     * touched by an incoming batch instead of rewriting the whole event.
     */
    fun resolveEntity(
        kind: EntityKind,
        entityId: String,
        ops: Collection<Operation>,
    ): OpPayload? {
        val winner = ops
            .asSequence()
            .filter { it.payload.entityKind == kind && it.payload.entityId == entityId }
            .maxWithOrNull(Operation.LwwOrder)
            ?: return null
        return when (winner.payload) {
            is OpPayload.EventDelete,
            is OpPayload.ParticipantDelete,
            is OpPayload.ExpenseDelete -> null
            else -> winner.payload
        }
    }
}
