package com.fairshare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
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
abstract class ExpenseDao {
    @Transaction
    @Query("SELECT * FROM expenses WHERE eventId = :eventId ORDER BY date DESC")
    abstract fun observeByEvent(eventId: String): Flow<List<ExpenseWithDetails>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    abstract suspend fun getById(id: String): ExpenseWithDetails?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertExpense(expense: ExpenseEntity)

    @Update
    abstract suspend fun updateExpense(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertShares(shares: List<ExpenseShareEntity>)

    @Delete
    abstract suspend fun deleteShares(shares: List<ExpenseShareEntity>)

    @Update
    abstract suspend fun updateShares(shares: List<ExpenseShareEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertItem(item: ExpenseItemEntity)

    @Update
    abstract suspend fun updateItem(item: ExpenseItemEntity)

    @Query("DELETE FROM expense_items WHERE id IN (:ids)")
    abstract suspend fun deleteItemsByIds(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAssignments(assignments: List<ExpenseItemAssignmentEntity>)

    @Delete
    abstract suspend fun deleteAssignments(assignments: List<ExpenseItemAssignmentEntity>)

    @Query("DELETE FROM expense_shares WHERE expenseId = :expenseId")
    abstract suspend fun deleteSharesFor(expenseId: String)

    @Query("DELETE FROM expense_items WHERE expenseId = :expenseId")
    abstract suspend fun deleteItemsFor(expenseId: String)

    @Query("DELETE FROM expenses WHERE id = :id")
    abstract suspend fun delete(id: String)

    /**
     * Upserts an expense together with its shares and per-article items.
     *
     * Unlike a delete-then-insert, this implementation diffs the new tree
     * against what is already persisted and only emits the strict minimum of
     * SQL writes: inserts for newly-added rows, updates for rows whose payload
     * actually changed (composite-key rows that already exist with identical
     * fields are skipped), and targeted deletes for rows no longer present.
     *
     * This matters for the CRDT materializer (DESIGN.md §5): concurrent
     * operations may converge by re-materializing an expense whose only one
     * field changed; we must not blow away unrelated shares/items just to
     * re-insert them with the same ids and content.
     *
     * The caller is responsible for assigning stable UUID ids to the expense
     * and each item; ids must be preserved across replays so the diff matches
     * the right rows.
     */
    @Transaction
    open suspend fun upsertWithDetails(
        expense: ExpenseEntity,
        shares: List<ExpenseShareEntity>,
        items: List<Pair<ExpenseItemEntity, List<String>>>,
    ) {
        val existing = getById(expense.id)
        if (existing == null) {
            insertExpense(expense)
        } else if (existing.expense != expense) {
            updateExpense(expense)
        }

        val shareDiff = ExpenseDetailsDiff.shares(
            expenseId = expense.id,
            incoming = shares,
            current = existing?.shares ?: emptyList(),
        )
        if (shareDiff.toDelete.isNotEmpty()) deleteShares(shareDiff.toDelete)
        if (shareDiff.toInsert.isNotEmpty()) insertShares(shareDiff.toInsert)
        if (shareDiff.toUpdate.isNotEmpty()) updateShares(shareDiff.toUpdate)

        val itemDiff = ExpenseDetailsDiff.items(
            expenseId = expense.id,
            incoming = items.map { it.first },
            current = existing?.items?.map { it.item } ?: emptyList(),
        )
        if (itemDiff.toDeleteIds.isNotEmpty()) deleteItemsByIds(itemDiff.toDeleteIds)
        itemDiff.toInsert.forEach { insertItem(it) }
        itemDiff.toUpdate.forEach { updateItem(it) }

        val currentAssignmentsByItem = existing?.items
            ?.associate { it.item.id to it.assignments }
            ?: emptyMap()
        for ((item, participantIds) in items) {
            val assignmentDiff = ExpenseDetailsDiff.assignments(
                itemId = item.id,
                incomingParticipantIds = participantIds,
                current = currentAssignmentsByItem[item.id] ?: emptyList(),
            )
            if (assignmentDiff.toDelete.isNotEmpty()) deleteAssignments(assignmentDiff.toDelete)
            if (assignmentDiff.toInsert.isNotEmpty()) insertAssignments(assignmentDiff.toInsert)
        }
    }
}
