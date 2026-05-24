package com.fairshare.data.repository

import com.fairshare.data.local.dao.CategoryDao
import com.fairshare.data.local.entity.CategoryEntity
import com.fairshare.data.sync.OperationApplier
import com.fairshare.domain.model.Category
import com.fairshare.domain.model.sync.CategorySnapshot
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

/**
 * Reads from Room, writes through [OperationApplier.applyLocal]. Same
 * pattern as ParticipantRepositoryImpl — categories are tiny entities
 * whose whole state fits in one snapshot, so a single Upsert op is
 * authoritative.
 */
class CategoryRepositoryImpl @Inject constructor(
    private val dao: CategoryDao,
    private val applier: OperationApplier,
) : CategoryRepository {

    override fun observeByEvent(eventId: String): Flow<List<Category>> =
        dao.observeByEvent(eventId)
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()

    override suspend fun getByEvent(eventId: String): List<Category> =
        dao.getByEvent(eventId).map { it.toDomain() }

    override suspend fun get(id: String): Category? = dao.getById(id)?.toDomain()

    override suspend fun upsert(category: Category): String {
        val id = category.id.ifBlank { UUID.randomUUID().toString() }
        applier.applyLocal(
            eventId = category.eventId,
            payload = OpPayload.CategoryUpsert(category.copy(id = id).toSnapshot()),
        )
        return id
    }

    override suspend fun delete(id: String) {
        val existing = dao.getById(id) ?: return
        applier.applyLocal(
            eventId = existing.eventId,
            payload = OpPayload.CategoryDelete(categoryId = id),
        )
    }
}

private fun CategoryEntity.toDomain() = Category(
    id = id,
    eventId = eventId,
    name = name,
    emoji = emoji,
    color = color,
    isDefault = false,
)

private fun Category.toSnapshot() = CategorySnapshot(
    id = id,
    eventId = eventId,
    name = name,
    emoji = emoji,
    color = color,
)
