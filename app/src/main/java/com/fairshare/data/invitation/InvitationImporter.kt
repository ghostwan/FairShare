package com.fairshare.data.invitation

import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.entity.EventEntity
import com.fairshare.data.sync.OperationApplier
import com.fairshare.domain.model.sync.OpOrigin
import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inbound counterpart of [InvitationExporter].
 *
 * Handles `fairshare://join` URLs: decodes the bundle, persists the
 * [EventEntity] with the embedded encryption key, then materializes
 * the seed ops through [OperationApplier]. The applier is idempotent
 * on opId so re-applying the same invitation is harmless.
 *
 * Ops are applied with origin [OpOrigin.LOCAL] so the regular push
 * pass forwards them to the Cloudflare Worker on the next sync; the
 * Worker dedupes by opId. After the first successful round-trip the
 * device behaves identically to one that created the event.
 */
@Singleton
class InvitationImporter @Inject constructor(
    private val eventDao: EventDao,
    private val applier: OperationApplier,
) {
    /** Failure modes [preview] / [apply] can hit. */
    sealed interface ImportError {
        data object MalformedUrl : ImportError
        data object MissingFields : ImportError
        data object SignatureMismatch : ImportError
        data class PayloadInvalid(val cause: Throwable) : ImportError
    }

    /** Result of a successful preview. */
    data class Preview(
        val eventId: String,
        val eventName: String?,
        val ops: List<Operation>,
    )

    /**
     * Decodes [url] and resolves the event metadata without writing
     * anything. Useful to show a confirmation screen before applying.
     */
    suspend fun preview(url: String): Result<Preview> {
        val decoded = InvitationCodec.decode(url)
            .getOrElse { return Result.failure(mapCodecError(it)) }
        // The user-visible name comes from the seed's EventUpsert if
        // present, falling back to an existing row (re-applying a
        // previously-joined invitation).
        val name = decoded.ops.asSequence()
            .map { it.payload }
            .filterIsInstance<OpPayload.EventUpsert>()
            .firstOrNull()
            ?.event
            ?.name
            ?: eventDao.getById(decoded.eventId)?.name
        return Result.success(Preview(decoded.eventId, name, decoded.ops))
    }

    /**
     * Persists the event row with its encryption key (so subsequent
     * materialization runs preserve the key) then applies the seed
     * ops as LOCAL origin.
     */
    suspend fun apply(url: String): Result<Preview> {
        val preview = preview(url).getOrElse { return Result.failure(it) }
        val decoded = InvitationCodec.decode(url).getOrThrow()
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
        applier.apply(preview.ops, OpOrigin.LOCAL)
        return Result.success(preview)
    }

    private fun mapCodecError(t: Throwable): ImportException {
        val codecError = (t as? DecodeException)?.error
        val mapped = when (codecError) {
            InvitationCodec.DecodeError.MalformedUrl -> ImportError.MalformedUrl
            InvitationCodec.DecodeError.MissingFields -> ImportError.MissingFields
            InvitationCodec.DecodeError.SignatureMismatch -> ImportError.SignatureMismatch
            is InvitationCodec.DecodeError.PayloadInvalid ->
                ImportError.PayloadInvalid(codecError.cause)
            null -> ImportError.PayloadInvalid(t)
        }
        return ImportException(mapped)
    }
}

internal class ImportException(val error: InvitationImporter.ImportError) :
    Exception(error.toString())
