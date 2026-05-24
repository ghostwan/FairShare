package com.fairshare.data.repository

import com.fairshare.data.local.dao.ExpenseDao
import com.fairshare.data.local.dao.ExpenseWithDetails
import com.fairshare.data.local.entity.ExpenseEntity
import com.fairshare.data.local.entity.ExpenseItemEntity
import com.fairshare.data.local.entity.ExpenseShareEntity
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.ExpenseItem
import com.fairshare.domain.model.ExpenseShare
import com.fairshare.domain.repository.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class ExpenseRepositoryImpl @Inject constructor(
    private val dao: ExpenseDao,
) : ExpenseRepository {

    override fun observeByEvent(eventId: String): Flow<List<Expense>> =
        dao.observeByEvent(eventId).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): Expense? = dao.getById(id)?.toDomain()

    override suspend fun add(expense: Expense): String = upsert(expense)
    override suspend fun update(expense: Expense) { upsert(expense) }

    override suspend fun delete(id: String) = dao.delete(id)

    private suspend fun upsert(expense: Expense): String {
        val expenseId = expense.id.ifBlank { UUID.randomUUID().toString() }
        val items = expense.items.map { item ->
            val itemId = item.id.ifBlank { UUID.randomUUID().toString() }
            val entity = ExpenseItemEntity(
                id = itemId,
                expenseId = expenseId,
                label = item.label,
                priceCents = item.priceCents,
                quantity = item.quantity,
                position = 0,
            )
            entity to item.assignedTo.toList()
        }
        dao.upsertWithDetails(
            expense.copy(id = expenseId).toEntity(),
            expense.shares.map { it.toEntity(expenseId) },
            items,
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

private fun Expense.toEntity() = ExpenseEntity(id, eventId, title, amountCents, payerId, date)
private fun ExpenseShare.toEntity(expenseId: String) =
    ExpenseShareEntity(expenseId, participantId, amountCents)
