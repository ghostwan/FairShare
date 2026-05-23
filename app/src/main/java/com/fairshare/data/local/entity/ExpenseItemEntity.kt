package com.fairshare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expense_items",
    foreignKeys = [ForeignKey(
        entity = ExpenseEntity::class,
        parentColumns = ["id"],
        childColumns = ["expenseId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("expenseId")],
)
data class ExpenseItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val label: String,
    val priceCents: Long,
    val quantity: Int,
    val position: Int,
)

@Entity(
    tableName = "expense_item_assignments",
    primaryKeys = ["itemId", "participantId"],
    foreignKeys = [ForeignKey(
        entity = ExpenseItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("itemId"), Index("participantId")],
)
data class ExpenseItemAssignmentEntity(
    val itemId: Long,
    val participantId: Long,
)
