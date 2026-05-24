package com.fairshare.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Row in the append-only op log. One row per [com.fairshare.domain.model.sync.Operation]
 * ever observed by this device, whether emitted locally or received from a
 * transport.
 *
 * The payload is stored as a JSON string (kotlinx-serialization output) to keep
 * the schema stable across additive changes to [OpPayload] sealed hierarchy.
 *
 * Indices:
 * - `(event_id, lamport)` powers ordered scans during materialization.
 * - `(event_id, applied)` powers the "what's left to materialize" query.
 *
 * Reference: DESIGN.md §3.3.
 */
@Entity(
    tableName = "operations",
    indices = [
        Index(value = ["event_id", "lamport"]),
        Index(value = ["event_id", "applied"]),
    ],
)
data class OperationEntity(
    @PrimaryKey @androidx.room.ColumnInfo(name = "op_id") val opId: String,
    @androidx.room.ColumnInfo(name = "event_id") val eventId: String,
    @androidx.room.ColumnInfo(name = "device_id") val deviceId: String,
    val lamport: Long,
    @androidx.room.ColumnInfo(name = "wall_clock_ms") val wallClockMs: Long,
    @androidx.room.ColumnInfo(name = "payload_json") val payloadJson: String,
    val applied: Boolean,
    /** One of [com.fairshare.domain.model.sync.OpOrigin] names. */
    val origin: String,
)
