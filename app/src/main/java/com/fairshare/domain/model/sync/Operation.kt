package com.fairshare.domain.model.sync

import kotlinx.serialization.Serializable

/**
 * A single CRDT operation. Immutable: once emitted, the envelope is appended to
 * the local op log and never mutated.
 *
 * Convergence is achieved via a Lamport pair `(lamport, deviceId)` (DESIGN.md
 * §4): the materializer keeps, per entity, the surviving op with the highest
 * pair lexicographically.
 *
 * @property opId Globally unique UUID v4 generated at emit time. Doubles as the
 *   idempotency key when transports redeliver an op.
 * @property eventId Scopes the op to one Event; cross-event ops do not exist.
 * @property deviceId Emitter device, also the LWW tiebreaker.
 * @property lamport Per-device monotonic counter; on receive `local = max(local,
 *   received) + 1` (DESIGN.md §4.1).
 * @property wallClockMs Best-effort wall clock for debugging only. Not used by
 *   the conflict resolution algorithm.
 * @property payload The mutation itself.
 */
@Serializable
data class Operation(
    val opId: String,
    val eventId: String,
    val deviceId: String,
    val lamport: Long,
    val wallClockMs: Long,
    val payload: OpPayload,
) {
    /**
     * Lexicographic comparator over `(lamport, deviceId)`. Higher values win.
     * Used by the materializer to pick the surviving op per entity.
     */
    companion object {
        val LwwOrder: Comparator<Operation> = Comparator { a, b ->
            val byLamport = a.lamport.compareTo(b.lamport)
            if (byLamport != 0) byLamport else a.deviceId.compareTo(b.deviceId)
        }
    }
}
