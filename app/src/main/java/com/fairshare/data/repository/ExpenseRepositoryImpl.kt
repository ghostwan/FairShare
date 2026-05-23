package com.fairshare.data.repository

import com.fairshare.data.local.dao.ExpenseDao
import com.fairshare.data.local.dao.ExpenseWithShares
import com.fairshare.data.local.entity.ExpenseEntity
import com.fairshare.data.local.entity.ExpenseShareEntity
import com.fairshare.domain.model.Expense
import com.fairshare.domain.model.ExpenseShare
import com.fairshare.domain.repository.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ExpenseRepositoryImpl @Inject constructor(
    private val dao: ExpenseDao,
) : ExpenseRepository {

    override fun observeByEvent(eventId: Long): Flow<List<Expense>> =
        dao.observeByEvent(eventId).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: Long): Expense? = dao.getById(id)?.toDomain()

    override suspend fun add(expense: Expense): Long {
        return dao.upsertWithShares(
            expense.toEntity(),
            expense.shares.map { it.toEntity(expense.id) },
        )
    }

    override suspend fun update(expense: Expense) {
        dao.upsertWithShares(
            expense.toEntity(),
            expense.shares.map { it.toEntity(expense.id) },
        )
    }

    override suspend fun delete(id: Long) = dao.delete(id)
}

private fun ExpenseWithShares.toDomain() = Expense(
    id = expense.id,
    eventId = expense.eventId,
    title = expense.title,
    amountCents = expense.amountCents,
    payerId = expense.payerId,
    date = expense.date,
    shares = shares.map { ExpenseShare(it.participantId, it.amountCents) },
)

private fun Expense.toEntity() = ExpenseEntity(id, eventId, title, amountCents, payerId, date)
private fun ExpenseShare.toEntity(expenseId: Long) =
    ExpenseShareEntity(expenseId, participantId, amountCents)
