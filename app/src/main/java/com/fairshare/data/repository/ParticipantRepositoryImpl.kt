package com.fairshare.data.repository

import com.fairshare.data.local.dao.ParticipantDao
import com.fairshare.data.local.entity.ParticipantEntity
import com.fairshare.data.sync.OperationApplier
import com.fairshare.domain.model.Participant
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.ParticipantSnapshot
import com.fairshare.domain.repository.ParticipantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

/**
 * Reads stay on the DAO. Writes go through [OperationApplier.applyLocal]
 * (DESIGN.md §5): each add / delete becomes an Operation appended to
 * the op log, then LWW-materialized into the `participants` table.
 *
 * [delete] does a small read from the DAO first to recover the
 * participant's `eventId`, because the op envelope must be scoped to an
 * event. Callers only carry the participant id around (UI lists do not
 * track event ids per row).
 */
class ParticipantRepositoryImpl @Inject constructor(
    private val dao: ParticipantDao,
    private val applier: OperationApplier,
) : ParticipantRepository {

    override fun observeByEvent(eventId: String): Flow<List<Participant>> =
        dao.observeByEvent(eventId)
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()

    override suspend fun getByEvent(eventId: String): List<Participant> =
        dao.getByEvent(eventId).map { it.toDomain() }

    override suspend fun add(participant: Participant): String {
        val id = participant.id.ifBlank { UUID.randomUUID().toString() }
        applier.applyLocal(
            eventId = participant.eventId,
            payload = OpPayload.ParticipantUpsert(
                participant.copy(id = id).toSnapshot(),
            ),
        )
        return id
    }

    override suspend fun delete(id: String) {
        val existing = dao.getById(id) ?: return
        applier.applyLocal(
            eventId = existing.eventId,
            payload = OpPayload.ParticipantDelete(participantId = id),
        )
    }
}

private fun ParticipantEntity.toDomain() = Participant(id, eventId, name)
private fun Participant.toSnapshot() = ParticipantSnapshot(id = id, eventId = eventId, name = name)
