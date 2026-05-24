package com.fairshare.data.sync

import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.entity.EventEntity
import com.fairshare.domain.model.sync.OpOrigin
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inbound counterpart of [SneakernetExporter].
 *
 * Handles both link kinds (DESIGN.md §6.1):
 *
 *   - `fairshare://sync` — incremental ops for an event the device
 *     has already joined. The encryption key must already be on file
 *     for HMAC verification.
 *
 *   - `fairshare://join` — invitation: carries the encryption key
 *     itself plus a seed op log. The device persists the event row
 *     with the key out-of-band (similar to local event creation in
 *     [com.fairshare.data.repository.EventRepositoryImpl]) before the
 *     seed ops are applied, so the materializer preserves the key.
 *
 * Both paths funnel into [OperationApplier.apply] with origin
 * SNEAKERNET; the applier is idempotent on opId, so re-applying the
 * same link is harmless.
 */
@Singleton
class SneakernetImporter @Inject constructor(
    private val eventDao: EventDao,
    private val applier: OperationApplier,
) {
    /** Failure modes [preview] / [apply] can hit. */
    sealed interface ImportError {
        data object MalformedUrl : ImportError
        data object MissingFields : ImportError
        data object SignatureMismatch : ImportError
        data object EventNotJoined : ImportError
        data class PayloadInvalid(val cause: Throwable) : ImportError
    }

    enum class Kind { SYNC, JOIN }

    /** Result of a successful preview. */
    data class Preview(
        val kind: Kind,
        val eventId: String,
        val eventName: String?,
        val ops: List<Operation>,
    )

    /**
     * Decodes [url] (sync or join) and resolves the event metadata,
     * without writing anything. Useful to show a confirmation screen
     * before applying.
     */
    suspend fun preview(url: String): Result<Preview> = when {
        url.startsWith("${SneakernetCodec.SCHEME}://${SneakernetCodec.JOIN_HOST}?") ->
            previewJoin(url)
        url.startsWith("${SneakernetCodec.SCHEME}://${SneakernetCodec.SYNC_HOST}?") ->
            previewSync(url)
        else -> Result.failure(ImportException(ImportError.MalformedUrl))
    }

    /**
     * Apply the ops carried by [url]. For join links, persists the
     * EventEntity with its encryption key before applying the seed ops
     * so the materializer can preserve the key on subsequent runs.
     */
    suspend fun apply(url: String): Result<Preview> {
        val preview = preview(url).getOrElse { return Result.failure(it) }

        if (preview.kind == Kind.JOIN) {
            // Find the EventUpsert snapshot in the seed to know the
            // event's user-visible metadata, then persist a stub row
            // with the encryption key extracted from the URL.
            val decoded = SneakernetCodec.decodeJoin(url).getOrThrow()
            val eventSnap = preview.ops.asSequence()
                .map { it.payload }
                .filterIsInstance<OpPayload.EventUpsert>()
                .firstOrNull()
                ?.event
            if (eventSnap != null && eventDao.getById(decoded.eventId) == null) {
                eventDao.insert(
                    EventEntity(
                        id = eventSnap.id,
                        name = eventSnap.name,
                        description = eventSnap.description,
                        currency = eventSnap.currency,
                        createdAt = eventSnap.createdAt,
                        encryptionKey = decoded.eventKey,
                    ),
                )
            }
        }

        applier.apply(preview.ops, OpOrigin.SNEAKERNET)
        return Result.success(preview)
    }

    private suspend fun previewSync(url: String): Result<Preview> {
        val eventId = peekEventId(url, SneakernetCodec.SYNC_HOST)
            ?: return Result.failure(ImportException(ImportError.MalformedUrl))
        val event = eventDao.getById(eventId)
            ?: return Result.failure(ImportException(ImportError.EventNotJoined))
        if (event.encryptionKey.isEmpty()) {
            return Result.failure(ImportException(ImportError.EventNotJoined))
        }
        val decoded = SneakernetCodec.decode(url, event.encryptionKey)
            .getOrElse { return Result.failure(mapCodecError(it)) }
        return Result.success(
            Preview(Kind.SYNC, decoded.eventId, event.name, decoded.ops),
        )
    }

    private suspend fun previewJoin(url: String): Result<Preview> {
        val decoded = SneakernetCodec.decodeJoin(url)
            .getOrElse { return Result.failure(mapCodecError(it)) }
        // The user-visible name comes from the seed's EventUpsert if
        // present, falling back to a row that might already exist
        // (re-applying an invitation we've already joined).
        val name = decoded.ops.asSequence()
            .map { it.payload }
            .filterIsInstance<OpPayload.EventUpsert>()
            .firstOrNull()
            ?.event
            ?.name
            ?: eventDao.getById(decoded.eventId)?.name
        return Result.success(
            Preview(Kind.JOIN, decoded.eventId, name, decoded.ops),
        )
    }

    private fun mapCodecError(t: Throwable): ImportException {
        val codecError = (t as? DecodeException)?.error
        val mapped = when (codecError) {
            SneakernetCodec.DecodeError.MalformedUrl -> ImportError.MalformedUrl
            SneakernetCodec.DecodeError.MissingFields -> ImportError.MissingFields
            SneakernetCodec.DecodeError.SignatureMismatch -> ImportError.SignatureMismatch
            is SneakernetCodec.DecodeError.PayloadInvalid ->
                ImportError.PayloadInvalid(codecError.cause)
            null -> ImportError.PayloadInvalid(t)
        }
        return ImportException(mapped)
    }

    /**
     * Extracts the `event` query parameter without validating the
     * signature. Used only to look up the local encryption key before
     * delegating to [SneakernetCodec] for the actual decode.
     */
    private fun peekEventId(url: String, host: String): String? {
        val prefix = "${SneakernetCodec.SCHEME}://$host?"
        if (!url.startsWith(prefix)) return null
        val query = url.substring(prefix.length)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0 || eq == pair.lastIndex) continue
            if (pair.substring(0, eq) == "event") return pair.substring(eq + 1)
        }
        return null
    }
}

internal class ImportException(val error: SneakernetImporter.ImportError) :
    Exception(error.toString())
