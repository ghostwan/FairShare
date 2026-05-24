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

    /**
     * Maximum lamport observed for `eventId` among ops with [origin]
     * (an [com.fairshare.domain.model.sync.OpOrigin] name). Used by
     * [com.fairshare.data.sync.WorkerCloudTransport] as the implicit
     * pull cursor: passing `since = maxLamportByOrigin(eventId, "CLOUD")`
     * to the Worker fetches every op the cloud has that we haven't seen
     * yet, with no extra cursor state to keep in sync.
     */
    @Query("SELECT MAX(lamport) FROM operations WHERE event_id = :eventId AND origin = :origin")
    suspend fun maxLamportByOrigin(eventId: String, origin: String): Long?

    /**
     * Lexicographically greatest `op_id` among ops with `(event_id,
     * origin, lamport)` matching the given values. Together with
     * [maxLamportByOrigin] it forms the composite `(lamport, op_id)`
     * pull cursor that [com.fairshare.data.sync.SyncCoordinator]
     * sends to the Worker — preventing data loss when more than
     * `PULL_PAGE_SIZE` cloud ops share the same lamport (the boundary
     * case where a strict `lamport > since` cursor would silently skip
     * the remainder on the next page).
     */
    @Query(
        "SELECT op_id FROM operations " +
            "WHERE event_id = :eventId AND origin = :origin AND lamport = :lamport " +
            "ORDER BY op_id DESC LIMIT 1",
    )
    suspend fun maxOpIdAtLamportByOrigin(
        eventId: String,
        origin: String,
        lamport: Long,
    ): String?

    @Query("SELECT COUNT(*) FROM operations WHERE event_id = :eventId")
    fun observeCount(eventId: String): Flow<Int>

    @Query("UPDATE operations SET applied = 1 WHERE op_id IN (:opIds)")
    suspend fun markApplied(opIds: List<String>)

    /**
     * Hard-removes every op for a given event. Used by the local-only
     * "remove from this device" action so that a subsequent re-import
     * (sneakernet or cloud) starts from a clean slate without losing to
     * an EventDelete tombstone via LWW.
     */
    @Query("DELETE FROM operations WHERE event_id = :eventId")
    suspend fun deleteAllForEvent(eventId: String)
}
