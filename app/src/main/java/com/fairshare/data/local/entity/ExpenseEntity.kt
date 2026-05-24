package com.fairshare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(
        entity = EventEntity::class,
        parentColumns = ["id"],
        childColumns = ["eventId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("eventId"), Index("payerId")],
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val title: String,
    val amountCents: Long,
    val payerId: String,
    val date: Long,
    val isSettlement: Boolean = false,
    /**
     * Optional category tag. Either a hardcoded default id (eg.
     * `default.food`) or a UUID pointing at a `CategoryEntity` row on
     * the same event. No FK is declared so that default ids stay
     * referentially valid without seeding the categories table, and
     * so a custom-category deletion just leaves the expense
     * uncategorized (we null it out manually in the repository).
     */
    val categoryId: String? = null,
)

@Entity(
    tableName = "expense_shares",
    primaryKeys = ["expenseId", "participantId"],
    foreignKeys = [ForeignKey(
        entity = ExpenseEntity::class,
        parentColumns = ["id"],
        childColumns = ["expenseId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("expenseId"), Index("participantId")],
)
data class ExpenseShareEntity(
    val expenseId: String,
    val participantId: String,
    val amountCents: Long,
)
