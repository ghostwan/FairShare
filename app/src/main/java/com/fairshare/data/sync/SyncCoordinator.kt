package com.fairshare.data.sync

import android.util.Log
import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.dao.OperationDao
import com.fairshare.data.local.entity.OperationEntity
import com.fairshare.domain.model.sync.OpOrigin
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import com.fairshare.domain.repository.CloudTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates a full sync cycle for one event against the
 * [CloudTransport] (DESIGN.md §6.2):
 *
 *   1. **Pull**: fetch every op the cloud has with `lamport > since`
 *      where `since = MAX(lamport) WHERE origin = CLOUD`. Decrypt each
 *      op; un-decryptable ops are skipped + logged but never abort the
 *      batch (CRDT convergence is monotonic — a future re-sync will
 *      pick them up if the user fixes the key). Successful ops flow
 *      through [OperationApplier] with origin [OpOrigin.CLOUD], which
 *      also catches the local Lamport clock up.
 *
 *   2. **Push**: re-emit every local op the cloud hasn't acknowledged
 *      yet — i.e. ops with `lamport > pushCursor` and origin `LOCAL`.
 *      Invitation-imported ops are also tagged LOCAL on the joining
 *      device so they propagate to the Worker on the next push; the
 *      Worker dedupes by opId. The push cursor is stored in
 *      [CloudCursorStore] and advanced monotonically after a successful
 *      push response.
 *
 * Per-event serialization: a single [Mutex] guards concurrent syncs
 * for the same event so an auto-pull (foreground) and a
 * pull-to-refresh started simultaneously do not double-encrypt or
 * double-push. Cross-event syncs are independent.
 *
 * Error handling: any IO / network failure surfaces as
 * `Result.failure`. The materialization side has its own internal
 * idempotency, so a partial sync followed by a retry never duplicates
 * application state.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val cloud: CloudTransport,
    private val eventDao: EventDao,
    private val operationDao: OperationDao,
    private val applier: OperationApplier,
    private val cursorStore: CloudCursorStore,
) {
    /** Per-event mutex map. Keyed by eventId so unrelated events run in parallel. */
    private val eventMutexes = mutableMapOf<String, Mutex>()
    private val mapLock = Mutex()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    /** Outcome of one sync cycle. Useful for UI status surfaces + tests. */
    data class SyncReport(
        val eventId: String,
        val pulled: Int,
        val pushed: Int,
        val skipped: Int,
    )

    /**
     * Runs a pull + push cycle for [eventId]. Returns a [SyncReport] on
     * success. Returns failure when:
     *
     *   - the event is unknown locally (cannot derive bearer / keys),
     *   - the cloud base URL is not configured,
     *   - the transport returns a network or HTTP error.
     */
    suspend fun syncEvent(eventId: String): Result<SyncReport> = runCatching {
        val event = eventDao.getById(eventId)
            ?: throw IllegalStateException("syncEvent: unknown event $eventId")
        val eventKey = event.encryptionKey
        if (eventKey.isEmpty()) {
            throw IllegalStateException("syncEvent: event $eventId has no encryption key")
        }

        val mutex = mutexFor(eventId)
        mutex.withLock {
            val bearer = SyncCrypto.computeWorkerBearer(eventKey, eventId)
            val cipherKey = SyncCrypto.deriveCloudCipherKey(eventKey)

            // Push first, then pull. The Worker uses a
            // register-on-first-use bearer scheme: a GET on an event
            // that never received a POST returns 401 `unknown_event`.
            // Always pushing first guarantees the bearer is registered
            // before we attempt to pull, even on a freshly-joined
            // event that has no local ops yet.
            val pushed = pushCycle(eventId, bearer, cipherKey)
            val (pulled, skipped) = pullCycle(eventId, bearer, cipherKey)
            SyncReport(eventId, pulled, pushed, skipped)
        }
    }

    /**
     * Convenience wrapper: syncs every event that has an encryption
     * key. Used by the app foreground hook and by WorkManager. Per-
     * event failures do not stop the loop.
     */
    suspend fun syncAllEvents(): List<Result<SyncReport>> {
        val snapshot = eventDao.observeAll().first()
        return snapshot.map { evt -> syncEvent(evt.id) }
    }

    private suspend fun pullCycle(
        eventId: String,
        bearer: String,
        cipherKey: ByteArray,
    ): Pair<Int, Int> {
        var pulled = 0
        var skipped = 0
        // Paginate until the Worker reports hasMore = false. We re-read
        // the local cursor on every iteration so it reflects the ops
        // we just applied. The cursor is composite `(lamport, opId)`:
        // a Lamport-only cursor would silently drop the remainder when
        // the Worker page boundary splits a group of ops sharing the
        // same lamport.
        while (true) {
            val since = operationDao.maxLamportByOrigin(eventId, OpOrigin.CLOUD.name) ?: 0L
            val sinceOp = if (since > 0L) {
                operationDao.maxOpIdAtLamportByOrigin(eventId, OpOrigin.CLOUD.name, since).orEmpty()
            } else {
                ""
            }
            val pullResult = cloud.pull(eventId, bearer, since, sinceOp)
            val page = pullResult.getOrElse { err ->
                // Defense in depth: if the Worker still thinks our
                // bearer is unknown (push race, or a transient
                // failure between the push and this pull), treat it
                // as "nothing to pull yet" rather than aborting the
                // whole sync cycle. The next sync will retry.
                if (err.message?.contains("unknown_event") == true) {
                    Log.i(
                        "SyncCoordinator",
                        "Pull for $eventId returned unknown_event; deferring to next sync",
                    )
                    return pulled to skipped
                }
                throw err
            }
            if (page.ops.isEmpty()) break

            val decoded = mutableListOf<Operation>()
            for (enc in page.ops) {
                val op = try {
                    CloudOpCodec.decrypt(enc, eventId, cipherKey)
                } catch (e: AEADBadTagException) {
                    Log.w(
                        "SyncCoordinator",
                        "Skipping un-decryptable op ${enc.opId} for $eventId: ${e.message}",
                    )
                    skipped++
                    null
                }
                if (op != null) decoded.add(op)
            }
            if (decoded.isNotEmpty()) {
                applier.apply(decoded, OpOrigin.CLOUD)
                pulled += decoded.size
            }
            if (!page.hasMore) break
        }
        return pulled to skipped
    }

    private suspend fun pushCycle(
        eventId: String,
        bearer: String,
        cipherKey: ByteArray,
    ): Int {
        val cursor = cursorStore.pushCursor(eventId)
        val candidates = operationDao.forEventSince(eventId, cursor)

        // Only forward ops emitted on this device (LOCAL). CLOUD-origin
        // ops already live on the Worker, re-pushing them would be
        // wasteful. Invitation-imported ops are also tagged LOCAL so
        // they get pushed once on the joining device's next sync; the
        // Worker dedupes by opId.
        val toPush = candidates
            .filter { it.origin == OpOrigin.LOCAL.name }
            .mapNotNull { entity -> entity.toOperationOrNull()?.let { entity to it } }

        val alreadyRegistered = cursorStore.isBearerRegistered(eventId)
        if (toPush.isEmpty()) {
            // Even with nothing to send, we may still need to
            // register our bearer with the Worker so subsequent pulls
            // are authorized. An empty-ops POST is the documented way
            // to do that (see `worker/src/index.ts` `handlePush`).
            if (!alreadyRegistered) {
                cloud.push(eventId, bearer, emptyList()).getOrElse { throw it }
                cursorStore.markBearerRegistered(eventId)
            }
            // Still advance the cursor past any CLOUD-only ops so we
            // don't re-scan them on every cycle.
            if (candidates.isNotEmpty()) {
                cursorStore.advancePushCursor(eventId, candidates.maxOf { it.lamport })
            }
            return 0
        }

        val encrypted = toPush.map { (_, op) -> CloudOpCodec.encrypt(op, cipherKey) }
        cloud.push(eventId, bearer, encrypted).getOrElse { throw it }
        if (!alreadyRegistered) cursorStore.markBearerRegistered(eventId)

        val highest = candidates.maxOf { it.lamport }
        cursorStore.advancePushCursor(eventId, highest)
        return toPush.size
    }

    private suspend fun mutexFor(eventId: String): Mutex = mapLock.withLock {
        eventMutexes.getOrPut(eventId) { Mutex() }
    }

    private fun OperationEntity.toOperationOrNull(): Operation? = runCatching {
        Operation(
            opId = opId,
            eventId = eventId,
            deviceId = deviceId,
            lamport = lamport,
            wallClockMs = wallClockMs,
            payload = json.decodeFromString(OpPayload.serializer(), payloadJson),
        )
    }.getOrNull()
}