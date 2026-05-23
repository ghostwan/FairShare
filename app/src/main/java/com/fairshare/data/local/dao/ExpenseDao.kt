package com.fairshare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.fairshare.data.local.entity.ExpenseEntity
import com.fairshare.data.local.entity.ExpenseShareEntity
import kotlinx.coroutines.flow.Flow

data class ExpenseWithShares(
    @androidx.room.Embedded val expense: ExpenseEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "expenseId")
    val shares: List<ExpenseShareEntity>,
)

@Dao
interface ExpenseDao {
    @Transaction
    @Query("SELECT * FROM expenses WHERE eventId = :eventId ORDER BY date DESC")
    fun observeByEvent(eventId: Long): Flow<List<ExpenseWithShares>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): ExpenseWithShares?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShares(shares: List<ExpenseShareEntity>)

    @Query("DELETE FROM expense_shares WHERE expenseId = :expenseId")
    suspend fun deleteSharesFor(expenseId: Long)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)

    @Transaction
    suspend fun upsertWithShares(expense: ExpenseEntity, shares: List<ExpenseShareEntity>): Long {
        val newId = insertExpense(expense)
        val finalId = if (expense.id == 0L) newId else expense.id
        deleteSharesFor(finalId)
        insertShares(shares.map { it.copy(expenseId = finalId) })
        return finalId
    }
}
