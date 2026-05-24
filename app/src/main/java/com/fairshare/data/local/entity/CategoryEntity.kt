package com.fairshare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted custom category (user-created). Default categories
 * ([com.fairshare.domain.model.DefaultCategories]) are NOT stored in
 * the database — they live in code and are referenced by id from
 * `ExpenseEntity.categoryId`.
 *
 * Materialized by [com.fairshare.data.sync.OperationApplier] from
 * `CategoryUpsert` / `CategoryDelete` ops; ON DELETE CASCADE on the
 * parent event mirrors the participant table semantics.
 */
@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(
        entity = EventEntity::class,
        parentColumns = ["id"],
        childColumns = ["eventId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("eventId")],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val name: String,
    val emoji: String,
    /** ARGB color as a Long, identical to the wire-format snapshot. */
    val color: Long,
)
