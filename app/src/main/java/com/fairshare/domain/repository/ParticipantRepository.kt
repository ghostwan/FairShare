package com.fairshare.domain.repository

import com.fairshare.domain.model.Participant
import kotlinx.coroutines.flow.Flow

interface ParticipantRepository {
    fun observeByEvent(eventId: String): Flow<List<Participant>>
    suspend fun getByEvent(eventId: String): List<Participant>
    /** Returns the id (generated if [Participant.id] is blank). */
    suspend fun add(participant: Participant): String
    /**
     * Updates a participant. The id must reference an existing row;
     * implementations propagate the change as an LWW upsert through
     * the standard op log so all paired devices converge.
     */
    suspend fun update(participant: Participant)
    suspend fun delete(id: String)
}
