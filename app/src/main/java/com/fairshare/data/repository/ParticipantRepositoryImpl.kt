package com.fairshare.data.repository

import com.fairshare.data.local.dao.ParticipantDao
import com.fairshare.data.local.entity.ParticipantEntity
import com.fairshare.domain.model.Participant
import com.fairshare.domain.repository.ParticipantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class ParticipantRepositoryImpl @Inject constructor(
    private val dao: ParticipantDao,
) : ParticipantRepository {
    override fun observeByEvent(eventId: String): Flow<List<Participant>> =
        dao.observeByEvent(eventId).map { list -> list.map { it.toDomain() } }

    override suspend fun getByEvent(eventId: String): List<Participant> =
        dao.getByEvent(eventId).map { it.toDomain() }

    override suspend fun add(participant: Participant): String {
        val id = participant.id.ifBlank { UUID.randomUUID().toString() }
        dao.insert(participant.copy(id = id).toEntity())
        return id
    }
    override suspend fun delete(id: String) = dao.delete(id)
}

private fun ParticipantEntity.toDomain() = Participant(id, eventId, name)
private fun Participant.toEntity() = ParticipantEntity(id, eventId, name)
