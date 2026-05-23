package com.fairshare.domain.repository

import com.fairshare.domain.model.Participant
import kotlinx.coroutines.flow.Flow

interface ParticipantRepository {
    fun observeByEvent(eventId: Long): Flow<List<Participant>>
    suspend fun getByEvent(eventId: Long): List<Participant>
    suspend fun add(participant: Participant): Long
    suspend fun delete(id: Long)
}
