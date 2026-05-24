package com.fairshare.domain.repository

import com.fairshare.domain.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * Reads / writes for **custom** categories. Default categories live
 * in [com.fairshare.domain.model.DefaultCategories] and never go
 * through this interface.
 *
 * Writes are CRDT ops emitted via the standard
 * [com.fairshare.data.sync.OperationApplier] pipeline.
 */
interface CategoryRepository {
    /** Observe all custom categories of the given event, sorted by name. */
    fun observeByEvent(eventId: String): Flow<List<Category>>

    suspend fun getByEvent(eventId: String): List<Category>

    suspend fun get(id: String): Category?

    /** Inserts or updates the category. Returns the (possibly generated) id. */
    suspend fun upsert(category: Category): String

    /** Tombstones the category. Expenses still referencing it become uncategorized at render time. */
    suspend fun delete(id: String)
}
