package com.fairshare.data.sync

import com.fairshare.data.local.dao.EventDao
import com.fairshare.domain.model.sync.OpOrigin
import com.fairshare.domain.model.sync.Operation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inbound counterpart of [SneakernetExporter].
 *
 * Decodes a `fairshare://sync` URL and feeds its ops into the local
 * [OperationApplier] with origin [OpOrigin.SNEAKERNET] (DESIGN.md
 * §6.1).
 *
 * Joining a new event from scratch needs the encryption key, which is
 * carried by the `fairshare://join` invitation link — not by sync
 * links. So a sync link can only be applied for an event the user has
 * already joined: we look up the event by id and abort with
 * [ImportError.EventNotJoined] if no key is on file. The HMAC check
 * inside [SneakernetCodec.decode] enforces that the link was produced
 * with the same key.
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

    /** Result of a successful preview. */
    data class Preview(
        val eventId: String,
        val eventName: String?,
        val ops: List<Operation>,
    )

    /**
     * Decodes [url] and resolves the event metadata, without writing
     * anything. Useful to show a confirmation screen before applying.
     */
    suspend fun preview(url: String): Result<Preview> {
        val eventId = peekEventId(url)
            ?: return Result.failure(ImportException(ImportError.MalformedUrl))
        val event = eventDao.getById(eventId)
            ?: return Result.failure(ImportException(ImportError.EventNotJoined))
        if (event.encryptionKey.isEmpty()) {
            return Result.failure(ImportException(ImportError.EventNotJoined))
        }
        val decoded = SneakernetCodec.decode(url, event.encryptionKey)
            .getOrElse { t ->
                val codecError = (t as? DecodeException)?.error
                val mapped = when (codecError) {
                    SneakernetCodec.DecodeError.MalformedUrl -> ImportError.MalformedUrl
                    SneakernetCodec.DecodeError.MissingFields -> ImportError.MissingFields
                    SneakernetCodec.DecodeError.SignatureMismatch -> ImportError.SignatureMismatch
                    is SneakernetCodec.DecodeError.PayloadInvalid ->
                        ImportError.PayloadInvalid(codecError.cause)
                    null -> ImportError.PayloadInvalid(t)
                }
                return Result.failure(ImportException(mapped))
            }
        return Result.success(Preview(decoded.eventId, event.name, decoded.ops))
    }

    /**
     * Apply the ops carried by [url]. The materializer is idempotent
     * (`opId` is PK with INSERT-IGNORE), so re-applying the same link
     * is safe — duplicate ops are dropped at insert time.
     */
    suspend fun apply(url: String): Result<Preview> {
        val preview = preview(url).getOrElse { return Result.failure(it) }
        applier.apply(preview.ops, OpOrigin.SNEAKERNET)
        return Result.success(preview)
    }

    /**
     * Extracts the `event` query parameter without validating the
     * signature. Used only to look up the local encryption key before
     * delegating to [SneakernetCodec] for the actual decode.
     */
    private fun peekEventId(url: String): String? {
        val prefix = "${SneakernetCodec.SCHEME}://${SneakernetCodec.SYNC_HOST}?"
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
