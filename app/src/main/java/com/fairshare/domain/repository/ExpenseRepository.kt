package com.fairshare.domain.repository

import com.fairshare.domain.model.Expense
import kotlinx.coroutines.flow.Flow

interface ExpenseRepository {
    fun observeByEvent(eventId: Long): Flow<List<Expense>>
    suspend fun get(id: Long): Expense?
    suspend fun add(expense: Expense): Long
    suspend fun update(expense: Expense)
    suspend fun delete(id: Long)
}
