package com.fairshare.data.invitation

import com.fairshare.data.local.dao.EventDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an invitation link for an event.
 *
 * Reads just the event encryption key and emits a compact URL via
 * [InvitationCodec.encode]: only the event id and the 32-byte key.
 * The op log is no longer embedded — the joining device pulls it from
 * the Cloudflare Worker on first sync (DESIGN.md §7). This keeps the
 * QR code small and constant-size regardless of event history.
 */
@Singleton
class InvitationExporter @Inject constructor(
    private val eventDao: EventDao,
) {
    /** Failure modes [export] can hit. */
    sealed interface ExportError {
        data object EventNotFound : ExportError
        data object EncryptionKeyMissing : ExportError
    }

    /**
     * Returns the invitation URL or a typed error. When [geminiKey] is
     * non-blank it is appended to the URL (with [geminiModel]) so the
     * invitee can reuse it for AI receipt scans.
     */
    suspend fun export(
        eventId: String,
        geminiKey: String? = null,
        geminiModel: String? = null,
    ): Result<Export> {
        val event = eventDao.getById(eventId)
            ?: return Result.failure(ExportException(ExportError.EventNotFound))
        if (event.encryptionKey.isEmpty()) {
            return Result.failure(ExportException(ExportError.EncryptionKeyMissing))
        }
        val url = InvitationCodec.encode(
            eventId = eventId,
            eventKey = event.encryptionKey,
            geminiKey = geminiKey,
            geminiModel = geminiModel,
        )
        return Result.success(Export(url = url))
    }

    data class Export(val url: String)
}

internal class ExportException(val error: InvitationExporter.ExportError) :
    Exception(error.toString())
