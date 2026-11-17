package com.fairshare.data.invitation

import android.util.Log
import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.entity.EventEntity
import com.fairshare.data.sync.PushTokenRegistrar
import com.fairshare.data.sync.SyncCoordinator
import com.fairshare.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inbound counterpart of [InvitationExporter].
 *
 * Handles `fairshare://join` (or `https://…/join`) URLs: decodes the
 * `event + key` bundle, persists an empty placeholder [EventEntity]
 * with the embedded encryption key, registers for FCM push on that
 * event, then kicks a [SyncCoordinator.syncEvent] cycle. The first
 * pull retrieves the whole op log from the Worker; the materializer
 * overwrites the placeholder name / currency / createdAt while
 * preserving the encryption key.
 *
 * The pull is idempotent on opId so re-importing the same invitation
 * is harmless. The placeholder row guarantees the event is visible
 * (with a fallback name) even if the device is offline during the
 * first sync attempt — the next foreground sync will fill in the
 * real metadata.
 */
@Singleton
class InvitationImporter @Inject constructor(
    private val eventDao: EventDao,
    private val coordinator: SyncCoordinator,
    private val pushRegistrar: PushTokenRegistrar,
    private val settings: SettingsRepository,
) {
    /** Failure modes [preview] / [apply] can hit. */
    sealed interface ImportError {
        data object MalformedUrl : ImportError
        data object MissingFields : ImportError
    }

    /** Result of a successful preview. */
    data class Preview(
        val eventId: String,
        /** Local name if the event is already known; null on a fresh join. */
        val eventName: String?,
        /** Gemini key embedded by the inviter, or null. */
        val sharedGeminiKey: String? = null,
        /** True when this device already has a Gemini key configured. */
        val hasLocalGeminiKey: Boolean = false,
    )

    /**
     * Decodes [url] and resolves the event metadata without writing
     * anything. The name is null on a fresh join (we only get it once
     * the first pull has materialized the EventUpsert op).
     */
    suspend fun preview(url: String): Result<Preview> {
        val decoded = InvitationCodec.decode(url)
            .getOrElse { return Result.failure(mapCodecError(it)) }
        val name = eventDao.getById(decoded.eventId)?.name
        return Result.success(
            Preview(
                eventId = decoded.eventId,
                eventName = name,
                sharedGeminiKey = decoded.geminiKey,
                hasLocalGeminiKey = settings.geminiApiKey.first().isNotBlank(),
            ),
        )
    }

    /**
     * Persists a placeholder event row with the embedded encryption
     * key, registers for FCM push, then runs a sync cycle. The cycle
     * registers the bearer with the Worker (mandatory push-empty
     * handshake) and pulls the whole op log.
     *
     * When [importGeminiKey] is true and the link carries a Gemini key,
     * that key (and its model, if any) replaces the local settings
     * before the sync starts.
     */
    suspend fun apply(url: String, importGeminiKey: Boolean = false): Result<Preview> {
        val decoded = InvitationCodec.decode(url)
            .getOrElse { return Result.failure(mapCodecError(it)) }

        if (importGeminiKey && decoded.geminiKey != null) {
            settings.setGeminiApiKey(decoded.geminiKey)
            decoded.geminiModel?.let { settings.setGeminiModel(it) }
        }

        if (eventDao.getById(decoded.eventId) == null) {
            eventDao.insert(
                EventEntity(
                    id = decoded.eventId,
                    // Placeholder fields — overwritten by the first pull
                    // which carries the EventUpsert op from the inviter.
                    name = "…",
                    description = null,
                    currency = "EUR",
                    createdAt = System.currentTimeMillis(),
                    encryptionKey = decoded.eventKey,
                ),
            )
        }

        // Register for FCM pushes so subsequent ops emitted by other
        // paired devices reach us without polling. Best-effort: a
        // missing token (no Play Services, denied permission) is
        // logged but not fatal.
        pushRegistrar.register(decoded.eventId).onFailure {
            Log.w("InvitationImporter", "FCM register failed for ${decoded.eventId}: ${it.message}")
        }

        // Push-then-pull: registers the bearer server-side, then
        // catches up on the full op log. Errors here surface to the
        // caller so the UI can retry.
        coordinator.syncEvent(decoded.eventId).onFailure {
            return Result.failure(it)
        }

        val finalName = eventDao.getById(decoded.eventId)?.name
        return Result.success(Preview(decoded.eventId, finalName))
    }

    private fun mapCodecError(t: Throwable): ImportException {
        val codecError = (t as? DecodeException)?.error
        val mapped = when (codecError) {
            InvitationCodec.DecodeError.MalformedUrl -> ImportError.MalformedUrl
            InvitationCodec.DecodeError.MissingFields -> ImportError.MissingFields
            null -> ImportError.MalformedUrl
        }
        return ImportException(mapped)
    }
}

internal class ImportException(val error: InvitationImporter.ImportError) :
    Exception(error.toString())
