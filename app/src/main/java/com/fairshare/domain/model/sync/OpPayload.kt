package com.fairshare.domain.model.sync

import kotlinx.serialization.Serializable

/**
 * Payload of an [Operation]. Each variant fully describes the mutation it
 * applies to the materialized state.
 *
 * For Upsert variants the snapshot is authoritative: the materializer keeps the
 * variant with the highest `(lamport, deviceId)` tuple per `entityId` and
 * writes its snapshot to the local DB (see DESIGN.md §4.2).
 *
 * Delete variants act as tombstones suppressing earlier Upserts for the same
 * entity, until a later Upsert revives it.
 */
@Serializable
sealed interface OpPayload {

    @Serializable
    data class EventUpsert(val event: EventSnapshot) : OpPayload

    @Serializable
    data class EventDelete(val eventId: String) : OpPayload

    @Serializable
    data class ParticipantUpsert(val participant: ParticipantSnapshot) : OpPayload

    @Serializable
    data class ParticipantDelete(val participantId: String) : OpPayload

    @Serializable
    data class ExpenseUpsert(val expense: ExpenseSnapshot) : OpPayload

    @Serializable
    data class ExpenseDelete(val expenseId: String) : OpPayload

    /**
     * Upsert / delete for a user-created custom Category. Default
     * categories are hardcoded ([com.fairshare.domain.model.DefaultCategories])
     * and never travel through this log.
     */
    @Serializable
    data class CategoryUpsert(val category: CategorySnapshot) : OpPayload

    @Serializable
    data class CategoryDelete(val categoryId: String) : OpPayload
}

/**
 * Returns the id of the entity this payload mutates. Used by the materializer
 * to group ops by entity for LWW resolution.
 */
val OpPayload.entityId: String
    get() = when (this) {
        is OpPayload.EventUpsert -> event.id
        is OpPayload.EventDelete -> eventId
        is OpPayload.ParticipantUpsert -> participant.id
        is OpPayload.ParticipantDelete -> participantId
        is OpPayload.ExpenseUpsert -> expense.id
        is OpPayload.ExpenseDelete -> expenseId
        is OpPayload.CategoryUpsert -> category.id
        is OpPayload.CategoryDelete -> categoryId
    }

/**
 * Entity family this payload belongs to. LWW resolution is performed
 * independently per family per entityId.
 */
enum class EntityKind { EVENT, PARTICIPANT, CATEGORY, EXPENSE }

val OpPayload.entityKind: EntityKind
    get() = when (this) {
        is OpPayload.EventUpsert,
        is OpPayload.EventDelete -> EntityKind.EVENT
        is OpPayload.ParticipantUpsert,
        is OpPayload.ParticipantDelete -> EntityKind.PARTICIPANT
        is OpPayload.CategoryUpsert,
        is OpPayload.CategoryDelete -> EntityKind.CATEGORY
        is OpPayload.ExpenseUpsert,
        is OpPayload.ExpenseDelete -> EntityKind.EXPENSE
    }
