package com.fairshare.domain.model.sync

/**
 * In-memory projection of an event's op log: the surviving snapshot per
 * entity after LWW resolution.
 *
 * Pure data class so it can be produced and asserted upon from JVM unit
 * tests without dragging Room in. The persistent counterpart lives in the
 * `events` / `participants` / `expenses` tables and is written by
 * `com.fairshare.data.sync.OperationApplier`.
 *
 * An entity that resolved to a tombstone (Delete winning over every
 * concurrent Upsert) is absent from the maps; the materializer treats
 * "absent" and "deleted" identically.
 */
data class MaterializedState(
    val events: Map<String, EventSnapshot> = emptyMap(),
    val participants: Map<String, ParticipantSnapshot> = emptyMap(),
    val expenses: Map<String, ExpenseSnapshot> = emptyMap(),
)
