package com.fairshare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fairshare.data.local.entity.ParticipantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ParticipantDao {
    @Query("SELECT * FROM participants WHERE eventId = :eventId ORDER BY name COLLATE NOCASE")
    fun observeByEvent(eventId: String): Flow<List<ParticipantEntity>>

    @Query("SELECT * FROM participants WHERE eventId = :eventId ORDER BY name COLLATE NOCASE")
    suspend fun getByEvent(eventId: String): List<ParticipantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(participant: ParticipantEntity)

    @Query("DELETE FROM participants WHERE id = :id")
    suspend fun delete(id: String)
}
