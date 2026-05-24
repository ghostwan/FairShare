package com.fairshare.data.invitation

import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.dao.OperationDao
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an invitation link for an event.
 *
 * Reads the full op log and the event's encryption key, then delegates
 * to [InvitationCodec.encode]. The resulting `fairshare://join` URL is
 * rendered as a QR code by the UI layer (see `EventDetailScreen`'s
 * share action) and scanned on the joining device.
 *
 * Strategy: export the *whole* log every time. Invitations are a cold
 * transport with no peer state, so we cannot know what the joining
 * device already has; relying on the recipient's `INSERT … ON CONFLICT
 * IGNORE` (opId PK) for dedup is simpler and correct, at the cost of
 * payload size. Once joined, ongoing sync goes through the Cloudflare
 * Worker (DESIGN.md §7).
 */
@Singleton
class InvitationExporter @Inject constructor(
    private val eventDao: EventDao,
    private val operationDao: OperationDao,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    /** Failure modes [export] can hit. */
    sealed interface ExportError {
        data object EventNotFound : ExportError
        data object EncryptionKeyMissing : ExportError
    }

    /**
     * Returns the invitation URL plus the number of ops it carries,
     * or a typed error. Empty op logs still produce a valid URL — the
     * recipient just sees an empty event.
     */
    suspend fun export(eventId: String): Result<Export> {
        val event = eventDao.getById(eventId)
            ?: return Result.failure(ExportException(ExportError.EventNotFound))
        if (event.encryptionKey.isEmpty()) {
            return Result.failure(ExportException(ExportError.EncryptionKeyMissing))
        }
        val ops = loadOps(eventId)
        val url = InvitationCodec.encode(eventId, ops, event.encryptionKey)
        return Result.success(Export(url = url, opCount = ops.size))
    }

    private suspend fun loadOps(eventId: String): List<Operation> =
        operationDao.forEvent(eventId).mapNotNull { entity ->
            runCatching {
                Operation(
                    opId = entity.opId,
                    eventId = entity.eventId,
                    deviceId = entity.deviceId,
                    lamport = entity.lamport,
                    wallClockMs = entity.wallClockMs,
                    payload = json.decodeFromString(
                        OpPayload.serializer(),
                        entity.payloadJson,
                    ),
                )
            }.getOrNull()
        }

    data class Export(val url: String, val opCount: Int)
}

internal class ExportException(val error: InvitationExporter.ExportError) :
    Exception(error.toString())
