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
    fun observeByEvent(eventId: String): Flow<List<ExpenseWithDetails>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: String): ExpenseWithDetails?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShares(shares: List<ExpenseShareEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ExpenseItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssignments(assignments: List<ExpenseItemAssignmentEntity>)

    @Query("DELETE FROM expense_shares WHERE expenseId = :expenseId")
    suspend fun deleteSharesFor(expenseId: String)

    @Query("DELETE FROM expense_items WHERE expenseId = :expenseId")
    suspend fun deleteItemsFor(expenseId: String)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Upserts an expense together with its shares and (optionally) per-article items.
     * Items and assignments are fully replaced on update.
     *
     * The caller is responsible for assigning ids (UUID strings) to the expense,
     * its shares (implicitly via composite key) and each item. This is required
     * because the same id must be reused on every replay for the upcoming CRDT
     * materializer to converge (see DESIGN.md §3 / step F).
     */
    @Transaction
    suspend fun upsertWithDetails(
        expense: ExpenseEntity,
        shares: List<ExpenseShareEntity>,
        items: List<Pair<ExpenseItemEntity, List<String>>>,
    ) {
        insertExpense(expense)

        deleteSharesFor(expense.id)
        insertShares(shares.map { it.copy(expenseId = expense.id) })

        deleteItemsFor(expense.id)
        items.forEachIndexed { idx, (item, participants) ->
            val positioned = item.copy(expenseId = expense.id, position = idx)
            insertItem(positioned)
            if (participants.isNotEmpty()) {
                insertAssignments(participants.map { pid ->
                    ExpenseItemAssignmentEntity(itemId = positioned.id, participantId = pid)
                })
            }
        }
    }
}
