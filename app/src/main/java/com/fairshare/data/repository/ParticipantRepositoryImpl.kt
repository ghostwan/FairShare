package com.fairshare.data.repository

import com.fairshare.data.local.dao.ParticipantDao
import com.fairshare.data.local.entity.ParticipantEntity
import com.fairshare.domain.model.Participant
import com.fairshare.domain.repository.ParticipantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ParticipantRepositoryImpl @Inject constructor(
    private val dao: ParticipantDao,
) : ParticipantRepository {
    override fun observeByEvent(eventId: Long): Flow<List<Participant>> =
        dao.observeByEvent(eventId).map { list -> list.map { it.toDomain() } }

    override suspend fun getByEvent(eventId: Long): List<Participant> =
        dao.getByEvent(eventId).map { it.toDomain() }

    override suspend fun add(participant: Participant): Long = dao.insert(participant.toEntity())
    override suspend fun delete(id: Long) = dao.delete(id)
}

private fun ParticipantEntity.toDomain() = Participant(id, eventId, name)
private fun Participant.toEntity() = ParticipantEntity(id, eventId, name)
