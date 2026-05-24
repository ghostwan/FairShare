package com.fairshare.data.local.dao

import com.fairshare.data.local.entity.ExpenseItemAssignmentEntity
import com.fairshare.data.local.entity.ExpenseItemEntity
import com.fairshare.data.local.entity.ExpenseShareEntity

/**
 * Pure diff helpers for [ExpenseDao.upsertWithDetails]. Kept separate from the
 * DAO so the algorithm can be unit-tested without Room / SQLite.
 *
 * Each function returns the three buckets the DAO needs to write: rows to
 * insert (new keys), rows to update (existing keys with different payloads),
 * and rows to delete (keys no longer present).
 *
 * Rationale: see DESIGN.md §5. Concurrent CRDT applies must not blow away
 * unrelated shares/items when re-materializing an expense.
 */
internal object ExpenseDetailsDiff {

    data class ShareDiff(
        val toInsert: List<ExpenseShareEntity>,
        val toUpdate: List<ExpenseShareEntity>,
        val toDelete: List<ExpenseShareEntity>,
    )

    fun shares(
        expenseId: String,
        incoming: List<ExpenseShareEntity>,
        current: List<ExpenseShareEntity>,
    ): ShareDiff {
        val normalized = incoming.map { it.copy(expenseId = expenseId) }
        val incomingByPid = normalized.associateBy { it.participantId }
        val currentByPid = current.associateBy { it.participantId }

        return ShareDiff(
            toInsert = normalized.filter { it.participantId !in currentByPid },
            toUpdate = normalized.filter { n ->
                val c = currentByPid[n.participantId]
                c != null && c.amountCents != n.amountCents
            },
            toDelete = current.filter { it.participantId !in incomingByPid },
        )
    }

    data class ItemDiff(
        val toInsert: List<ExpenseItemEntity>,
        val toUpdate: List<ExpenseItemEntity>,
        val toDeleteIds: List<String>,
    )

    fun items(
        expenseId: String,
        incoming: List<ExpenseItemEntity>,
        current: List<ExpenseItemEntity>,
    ): ItemDiff {
        val normalized = incoming.mapIndexed { idx, item ->
            item.copy(expenseId = expenseId, position = idx)
        }
        val incomingIds = normalized.map { it.id }.toSet()
        val currentById = current.associateBy { it.id }

        return ItemDiff(
            toInsert = normalized.filter { it.id !in currentById },
            toUpdate = normalized.filter { n ->
                val c = currentById[n.id]
                c != null && c != n
            },
            toDeleteIds = current.map { it.id }.filter { it !in incomingIds },
        )
    }

    data class AssignmentDiff(
        val toInsert: List<ExpenseItemAssignmentEntity>,
        val toDelete: List<ExpenseItemAssignmentEntity>,
    )

    fun assignments(
        itemId: String,
        incomingParticipantIds: List<String>,
        current: List<ExpenseItemAssignmentEntity>,
    ): AssignmentDiff {
        val incomingSet = incomingParticipantIds.toSet()
        val currentSet = current.map { it.participantId }.toSet()
        return AssignmentDiff(
            toInsert = (incomingSet - currentSet).map {
                ExpenseItemAssignmentEntity(itemId = itemId, participantId = it)
            },
            toDelete = current.filter { it.participantId !in incomingSet },
        )
    }
}
