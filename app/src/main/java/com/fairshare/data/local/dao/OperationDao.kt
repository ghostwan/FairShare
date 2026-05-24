package com.fairshare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fairshare.data.local.entity.OperationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Append-only access to the op log. The materializer (see
 * `com.fairshare.data.sync.OperationApplier` — step F) is the only writer that
 * sets [OperationEntity.applied] to `true`.
 *
 * [insertAll] uses [OnConflictStrategy.IGNORE] because transports may redeliver
 * the same op; the `opId` primary key gives us idempotency for free.
 */
@Dao
interface OperationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(ops: List<OperationEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(op: OperationEntity): Long

    @Query("SELECT * FROM operations WHERE event_id = :eventId ORDER BY lamport ASC, device_id ASC")
    suspend fun forEvent(eventId: String): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE event_id = :eventId AND applied = 0 ORDER BY lamport ASC, device_id ASC")
    suspend fun pendingForEvent(eventId: String): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE event_id = :eventId AND lamport > :sinceLamport ORDER BY lamport ASC, device_id ASC")
    suspend fun forEventSince(eventId: String, sinceLamport: Long): List<OperationEntity>

    @Query("SELECT MAX(lamport) FROM operations WHERE event_id = :eventId")
    suspend fun maxLamport(eventId: String): Long?

    @Query("SELECT COUNT(*) FROM operations WHERE event_id = :eventId")
    fun observeCount(eventId: String): Flow<Int>

    @Query("UPDATE operations SET applied = 1 WHERE op_id IN (:opIds)")
    suspend fun markApplied(opIds: List<String>)
}
