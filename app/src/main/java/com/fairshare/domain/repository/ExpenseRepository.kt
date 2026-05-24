package com.fairshare.domain.repository

import com.fairshare.domain.model.Expense
import kotlinx.coroutines.flow.Flow

interface ExpenseRepository {
    fun observeByEvent(eventId: String): Flow<List<Expense>>
    suspend fun get(id: String): Expense?
    /** Returns the id (generated if [Expense.id] is blank). */
    suspend fun add(expense: Expense): String
    suspend fun update(expense: Expense)
    suspend fun delete(id: String)
}
