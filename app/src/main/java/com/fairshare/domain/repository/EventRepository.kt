package com.fairshare.domain.repository

import com.fairshare.domain.model.Event
import kotlinx.coroutines.flow.Flow

interface EventRepository {
    fun observeEvents(): Flow<List<Event>>
    fun observeEvent(id: Long): Flow<Event?>
    suspend fun create(event: Event): Long
    suspend fun update(event: Event)
    suspend fun delete(id: Long)
}
