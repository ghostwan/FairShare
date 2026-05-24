package com.fairshare.data.sync

import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.dao.OperationDao
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds sneakernet bundles for an event (DESIGN.md §6.1).
 *
 * Reads the full op log for the event and the event's local encryption
 * key, then delegates to [SneakernetCodec.encode]. The result is a
 * single `fairshare://sync` URL that can be shared as text, copied to
 * the clipboard, or rendered as a QR code by the UI layer.
 *
 * Phase 1 strategy: export the *whole* log every time. Sneakernet is a
 * cold transport with no peer state, so we cannot know which ops the
 * recipient already has; relying on the recipient's `INSERT ... ON
 * CONFLICT IGNORE` (opId is PK) for deduplication is simpler and
 * correct, at the cost of bandwidth. Per-peer high-water-marks become
 * relevant only with the Worker (phase 2).
 */
@Singleton
class SneakernetExporter @Inject constructor(
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
     * Returns the bundle URL plus the number of ops it carries, or a
     * typed error. Empty op logs still produce a valid URL — the
     * recipient just sees nothing new.
     */
    suspend fun export(eventId: String): Result<Export> {
        val event = eventDao.getById(eventId)
            ?: return Result.failure(ExportException(ExportError.EventNotFound))
        if (event.encryptionKey.isEmpty()) {
            return Result.failure(ExportException(ExportError.EncryptionKeyMissing))
        }

        val ops: List<Operation> = operationDao.forEvent(eventId)
            .mapNotNull { entity ->
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

        val url = SneakernetCodec.encode(eventId, ops, event.encryptionKey)
        return Result.success(Export(url = url, opCount = ops.size))
    }

    data class Export(val url: String, val opCount: Int)
}

internal class ExportException(val error: SneakernetExporter.ExportError) :
    Exception(error.toString())
