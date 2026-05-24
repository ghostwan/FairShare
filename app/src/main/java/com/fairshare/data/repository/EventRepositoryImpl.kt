package com.fairshare.data.repository

import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.entity.EventEntity
import com.fairshare.domain.model.Event
import com.fairshare.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class EventRepositoryImpl @Inject constructor(
    private val dao: EventDao,
) : EventRepository {

    override fun observeEvents(): Flow<List<Event>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeEvent(id: String): Flow<Event?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun create(event: Event): String {
        val id = event.id.ifBlank { UUID.randomUUID().toString() }
        dao.insert(event.copy(id = id).toEntity())
        return id
    }
    override suspend fun update(event: Event) = dao.update(event.toEntity())
    override suspend fun delete(id: String) = dao.delete(id)
}

private fun EventEntity.toDomain() = Event(id, name, description, currency, createdAt)
private fun Event.toEntity() = EventEntity(id, name, description, currency, createdAt)
