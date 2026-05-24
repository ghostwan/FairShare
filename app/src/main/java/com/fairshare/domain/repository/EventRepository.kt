package com.fairshare.domain.repository

import com.fairshare.domain.model.Event
import kotlinx.coroutines.flow.Flow

interface EventRepository {
    fun observeEvents(): Flow<List<Event>>
    fun observeEvent(id: String): Flow<Event?>
    /** Returns the id (generated if [Event.id] is blank). */
    suspend fun create(event: Event): String
    suspend fun update(event: Event)
    suspend fun delete(id: String)
}
