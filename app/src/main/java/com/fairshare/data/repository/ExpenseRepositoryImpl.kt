package com.fairshare.data.repository

import com.fairshare.data.local.dao.ExpenseDao
import com.fairshare.data.local.dao.ExpenseWithDetails
import com.fairshare.data.sync.OperationApplier
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.ExpenseItem
import com.fairshare.domain.model.ExpenseShare
import com.fairshare.domain.model.sync.ExpenseItemSnapshot
import com.fairshare.domain.model.sync.ExpenseShareSnapshot
import com.fairshare.domain.model.sync.ExpenseSnapshot
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.repository.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

/**
 * Reads stay on the DAO. Writes go through [OperationApplier.applyLocal]
 * (DESIGN.md §5).
 *
 * An expense is the atomic CRDT unit: one [OpPayload.ExpenseUpsert]
 * carries the whole tree (shares + items + assignments). Concurrent
 * edits on the same expense resolve via LWW on the envelope.
 *
 * Ids are generated client-side when blank so two devices creating
 * different expenses never collide; they must remain stable across
 * replays so the materializer's diff (see `ExpenseDetailsDiff`) matches
 * the right rows.
 */
class ExpenseRepositoryImpl @Inject constructor(
    private val dao: ExpenseDao,
    private val applier: OperationApplier,
) : ExpenseRepository {

    override fun observeByEvent(eventId: String): Flow<List<Expense>> =
        dao.observeByEvent(eventId)
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()

    override suspend fun get(id: String): Expense? = dao.getById(id)?.toDomain()

    override suspend fun add(expense: Expense): String = upsert(expense)

    override suspend fun update(expense: Expense) {
        upsert(expense)
    }

    override suspend fun delete(id: String) {
        val existing = dao.getById(id) ?: return
        applier.applyLocal(
            eventId = existing.expense.eventId,
            payload = OpPayload.ExpenseDelete(expenseId = id),
        )
    }

    private suspend fun upsert(expense: Expense): String {
        val expenseId = expense.id.ifBlank { UUID.randomUUID().toString() }
        val itemsWithIds = expense.items.map { item ->
            item.copy(id = item.id.ifBlank { UUID.randomUUID().toString() })
        }
        val snapshot = ExpenseSnapshot(
            id = expenseId,
            eventId = expense.eventId,
            title = expense.title,
            amountCents = expense.amountCents,
            payerId = expense.payerId,
            date = expense.date,
            shares = expense.shares.map { it.toSnapshot() },
            items = itemsWithIds.map { it.toSnapshot() },
        )
        applier.applyLocal(
            eventId = expense.eventId,
            payload = OpPayload.ExpenseUpsert(snapshot),
        )
        return expenseId
    }
}

private fun ExpenseWithDetails.toDomain(): Expense {
    val sortedItems = items.sortedBy { it.item.position }.map { iwa ->
        ExpenseItem(
            id = iwa.item.id,
            label = iwa.item.label,
            priceCents = iwa.item.priceCents,
            quantity = iwa.item.quantity,
            assignedTo = iwa.assignments.map { it.participantId }.toSet(),
        )
    }
    return Expense(
        id = expense.id,
        eventId = expense.eventId,
        title = expense.title,
        amountCents = expense.amountCents,
        payerId = expense.payerId,
        date = expense.date,
        shares = shares.map { ExpenseShare(it.participantId, it.amountCents) },
        items = sortedItems,
    )
}

private fun ExpenseShare.toSnapshot() = ExpenseShareSnapshot(
    id = UUID.randomUUID().toString(),
    participantId = participantId,
    amountCents = amountCents,
)

private fun ExpenseItem.toSnapshot() = ExpenseItemSnapshot(
    id = id,
    label = label,
    priceCents = priceCents,
    quantity = quantity,
    assignedTo = assignedTo,
)
