package com.fairshare.data.sync

import com.fairshare.domain.repository.CloudTransport
import com.fairshare.domain.repository.CloudTransport.EncryptedOp
import com.fairshare.domain.repository.CloudTransport.PullResult
import com.fairshare.domain.repository.CloudTransport.PushResult
import com.fairshare.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp implementation of [CloudTransport] talking to the FairShare
 * sync Worker (see `worker/src/index.ts`).
 *
 * URLs are built per request from [SettingsRepository.cloudBaseUrl] so
 * the user can repoint the app at a self-hosted or staging Worker
 * without restarting. The OkHttp client is shared with the Gemini
 * parser (see `RepositoryModule.provideOkHttpClient`) — same timeouts,
 * same connection pool.
 *
 * Wire format: standard base64 for `nonce` and `ciphertext` (matches
 * what the Worker's `base64ToBytes` / `bytesToBase64` produce). We use
 * [Base64.NO_WRAP] to avoid the `\n` injection that the default flag
 * adds, which would otherwise inflate request bodies and break the
 * Worker's strict base64 regex.
 *
 * Result handling: every failure mode — IO, non-2xx, malformed JSON,
 * unexpected shape — surfaces as `Result.failure` with the original
 * exception. The sync coordinator decides whether to retry, defer to
 * WorkManager, or surface to the UI.
 */
@Singleton
class WorkerCloudTransport @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
) : CloudTransport {

    private val json: Json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class PushOpWire(
        val opId: String,
        val lamport: Long,
        val deviceId: String,
        val nonce: String,
        val ciphertext: String,
    )

    @Serializable
    private data class PushBodyWire(val ops: List<PushOpWire>)

    @Serializable
    private data class PushResponseWire(val inserted: Int = 0)

    @Serializable
    private data class PullOpWire(
        val opId: String,
        val lamport: Long,
        val deviceId: String,
        val nonce: String,
        val ciphertext: String,
    )

    @Serializable
    private data class PullResponseWire(
        val ops: List<PullOpWire> = emptyList(),
        val nextSince: Long = 0,
        val nextSinceOp: String = "",
        val hasMore: Boolean = false,
    )

    override suspend fun push(
        eventId: String,
        bearer: String,
        ops: List<EncryptedOp>,
    ): Result<PushResult> = runCatching {
        val baseUrl = baseUrlOrThrow()
        val body = PushBodyWire(
            ops = ops.map {
                PushOpWire(
                    opId = it.opId,
                    lamport = it.lamport,
                    deviceId = it.deviceId,
                    nonce = b64(it.nonce),
                    ciphertext = b64(it.ciphertext),
                )
            },
        )
        val payload = json.encodeToString(PushBodyWire.serializer(), body)
        val request = Request.Builder()
            .url("$baseUrl/events/$eventId/ops")
            .header("authorization", "Bearer $bearer")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val text = execute(request)
        val parsed = try {
            json.decodeFromString(PushResponseWire.serializer(), text)
        } catch (e: SerializationException) {
            throw IOException("Malformed push response: ${text.take(200)}", e)
        }
        PushResult(inserted = parsed.inserted)
    }

    override suspend fun pull(
        eventId: String,
        bearer: String,
        since: Long,
        sinceOp: String,
    ): Result<PullResult> = runCatching {
        val baseUrl = baseUrlOrThrow()
        val urlBuilder = StringBuilder("$baseUrl/events/$eventId/ops?since=$since")
        if (sinceOp.isNotEmpty()) {
            urlBuilder.append("&since_op=").append(sinceOp)
        }
        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("authorization", "Bearer $bearer")
            .get()
            .build()
        val text = execute(request)
        val parsed = try {
            json.decodeFromString(PullResponseWire.serializer(), text)
        } catch (e: SerializationException) {
            throw IOException("Malformed pull response: ${text.take(200)}", e)
        }
        PullResult(
            ops = parsed.ops.map {
                EncryptedOp(
                    opId = it.opId,
                    lamport = it.lamport,
                    deviceId = it.deviceId,
                    nonce = unb64(it.nonce),
                    ciphertext = unb64(it.ciphertext),
                )
            },
            nextSince = parsed.nextSince,
            nextSinceOp = parsed.nextSinceOp,
            hasMore = parsed.hasMore,
        )
    }

    /**
     * Runs the call on [Dispatchers.IO] and converts non-2xx responses
     * into [IOException] with the server's error code embedded so the
     * caller can log it.
     */
    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${request.url}: ${body.take(200)}")
            }
            body
        }
    }

    private suspend fun baseUrlOrThrow(): String {
        val raw = settings.cloudBaseUrl.first().trimEnd('/')
        if (raw.isEmpty()) throw IOException("Cloud base URL not configured")
        return raw
    }

    private fun b64(bytes: ByteArray): String =
        bytes.toByteString(0, bytes.size).base64()

    /** Returns empty bytes on invalid base64; the Worker only ever sends valid b64. */
    private fun unb64(s: String): ByteArray =
        s.decodeBase64()?.toByteArray() ?: ByteArray(0)

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
