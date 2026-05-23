package com.fairshare.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.fairshare.data.local.entity.ExpenseEntity
import com.fairshare.data.local.entity.ExpenseItemAssignmentEntity
import com.fairshare.data.local.entity.ExpenseItemEntity
import com.fairshare.data.local.entity.ExpenseShareEntity
import kotlinx.coroutines.flow.Flow

data class ExpenseItemWithAssignments(
    @Embedded val item: ExpenseItemEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId")
    val assignments: List<ExpenseItemAssignmentEntity>,
)

data class ExpenseWithDetails(
    @Embedded val expense: ExpenseEntity,
    @Relation(parentColumn = "id", entityColumn = "expenseId")
    val shares: List<ExpenseShareEntity>,
    @Relation(
        entity = ExpenseItemEntity::class,
        parentColumn = "id",
        entityColumn = "expenseId",
    )
    val items: List<ExpenseItemWithAssignments>,
)

@Dao
interface ExpenseDao {
    @Transaction
    @Query("SELECT * FROM expenses WHERE eventId = :eventId ORDER BY date DESC")
    fun observeByEvent(eventId: Long): Flow<List<ExpenseWithDetails>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): ExpenseWithDetails?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShares(shares: List<ExpenseShareEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ExpenseItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssignments(assignments: List<ExpenseItemAssignmentEntity>)

    @Query("DELETE FROM expense_shares WHERE expenseId = :expenseId")
    suspend fun deleteSharesFor(expenseId: Long)

    @Query("DELETE FROM expense_items WHERE expenseId = :expenseId")
    suspend fun deleteItemsFor(expenseId: Long)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Upserts an expense together with its shares and (optionally) per-article items.
     * Items and assignments are fully replaced on update.
     */
    @Transaction
    suspend fun upsertWithDetails(
        expense: ExpenseEntity,
        shares: List<ExpenseShareEntity>,
        items: List<Pair<ExpenseItemEntity, List<Long>>>,
    ): Long {
        val newId = insertExpense(expense)
        val finalId = if (expense.id == 0L) newId else expense.id

        deleteSharesFor(finalId)
        insertShares(shares.map { it.copy(expenseId = finalId) })

        deleteItemsFor(finalId)
        items.forEachIndexed { idx, (item, participants) ->
            val itemId = insertItem(item.copy(id = 0L, expenseId = finalId, position = idx))
            if (participants.isNotEmpty()) {
                insertAssignments(participants.map { pid ->
                    ExpenseItemAssignmentEntity(itemId = itemId, participantId = pid)
                })
            }
        }
        return finalId
    }
}
