package com.fairshare.domain.repository

/**
 * Abstracts the Cloudflare Worker HTTP transport (DESIGN.md §6.2).
 *
 * The transport ferries opaque encrypted blobs between devices. It has
 * no knowledge of the plaintext payload, the event encryption key, or
 * the materialized state — all of that lives one layer up in
 * [com.fairshare.data.sync.SyncCoordinator]. This split keeps the
 * transport trivially mockable for tests and lets us swap in different
 * backends (e.g. self-hosted) without touching the CRDT pipeline.
 *
 * Bearer derivation, op encryption, and cursor bookkeeping all happen
 * in the coordinator. The transport just sees:
 *
 *   - a pre-computed bearer (per-event, static),
 *   - already-encrypted op envelopes for push,
 *   - opaque `since` cursors for pull.
 *
 * All methods are suspend + return [Result] rather than throwing so
 * callers can decide whether a particular failure (timeout, offline,
 * 401, malformed response) should be retried, surfaced, or silently
 * deferred.
 */
interface CloudTransport {

    /**
     * Wire shape of one op as exchanged with the Worker. `nonce` and
     * `ciphertext` are produced by
     * [com.fairshare.data.sync.CloudOpCodec.encrypt] and consumed by
     * [com.fairshare.data.sync.CloudOpCodec.decrypt]; the transport
     * never inspects them.
     */
    data class EncryptedOp(
        val opId: String,
        val lamport: Long,
        val deviceId: String,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    ) {
        // ByteArray fields force a manual equals/hashCode for sane test
        // assertions; without these, two semantically equal instances
        // would compare unequal because of reference equality on arrays.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedOp) return false
            return opId == other.opId &&
                lamport == other.lamport &&
                deviceId == other.deviceId &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int {
            var h = opId.hashCode()
            h = 31 * h + lamport.hashCode()
            h = 31 * h + deviceId.hashCode()
            h = 31 * h + nonce.contentHashCode()
            h = 31 * h + ciphertext.contentHashCode()
            return h
        }
    }

    data class PushResult(val inserted: Int)

    data class PullResult(
        val ops: List<EncryptedOp>,
        /**
         * Composite cursor `(lamport, opId)` to feed back into the
         * next [pull] call. Always monotonic; when [hasMore] is false
         * the caller can stop paging until it has new ops to push.
         *
         * The opId tiebreaker matters when the Worker page boundary
         * splits a group of ops sharing the same lamport — using only
         * `nextSince` would silently drop the remainder on the next
         * request (`lamport > since` is strict).
         */
        val nextSince: Long,
        val nextSinceOp: String,
        val hasMore: Boolean,
    )

    /**
     * POST `/events/{eventId}/ops`. Idempotent on `opId` server-side,
     * so retrying a batch after a transient failure is safe.
     */
    suspend fun push(
        eventId: String,
        bearer: String,
        ops: List<EncryptedOp>,
    ): Result<PushResult>

    /**
     * GET `/events/{eventId}/ops?since={since}[&since_op={sinceOp}]`.
     * Returns ops greater than `(since, sinceOp)` in `(lamport, opId)`
     * lexicographic order. Pass `sinceOp = ""` on the very first call
     * for an event to start from the beginning of the log. The Worker
     * pages with `hasMore` so callers should loop until it returns
     * false, feeding back both [PullResult.nextSince] and
     * [PullResult.nextSinceOp].
     */
    suspend fun pull(
        eventId: String,
        bearer: String,
        since: Long,
        sinceOp: String,
    ): Result<PullResult>
}
