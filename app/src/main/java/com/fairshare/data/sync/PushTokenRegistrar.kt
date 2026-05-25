package com.fairshare.data.sync

import android.util.Log
import com.fairshare.data.local.dao.EventDao
import com.fairshare.domain.repository.SettingsRepository
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Registers and unregisters this device's FCM token with the sync
 * Worker so that an op pushed by a peer triggers a push notification
 * to every other paired device (see `worker/src/index.ts`
 * `notifyPairedDevices`). The Worker maps `(eventId, deviceId)` to one
 * token; calling [register] again simply overwrites the previous
 * token, which is what FCM expects after a token rotation.
 *
 * Auth: the Worker uses the same per-event bearer scheme as for
 * push/pull (see [SyncCrypto.computeWorkerBearer]). The bearer is
 * register-on-first-use, so we can call this before any op has been
 * pushed and the Worker will accept it.
 *
 * Failure handling: every method returns a [Result]. A failed call is
 * non-fatal — the polling fallback (until Commit 3 removes it) still
 * picks up changes, and a future op push will re-register the device
 * implicitly because [SyncCoordinator] uses a separate codepath. The
 * caller decides whether to retry; foreground callers typically log
 * and move on.
 */
@Singleton
class PushTokenRegistrar @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val identity: SyncIdentityStore,
    private val eventDao: EventDao,
) {
    private val json: Json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TokenBodyWire(val fcmToken: String)

    /**
     * Registers (or refreshes) the FCM token for [eventId]. Fetches a
     * fresh token from FCM via [FirebaseMessaging.getToken] so this is
     * safe to call at any time, including just after `onNewToken`.
     */
    suspend fun register(eventId: String): Result<Unit> = runCatching {
        val token = fetchFcmToken()
        sendPut(eventId, token)
    }

    /**
     * Registers the supplied [token] for [eventId] without re-querying
     * FCM. Used by [FairShareMessagingService.onNewToken] which already
     * has the fresh token in hand.
     */
    suspend fun registerWithToken(eventId: String, token: String): Result<Unit> = runCatching {
        sendPut(eventId, token)
    }

    /**
     * Unregisters this device's token for [eventId]. Called when the
     * user removes the event locally so the Worker stops fanning out
     * pushes to a device that no longer cares.
     */
    suspend fun unregister(eventId: String): Result<Unit> = runCatching {
        val baseUrl = baseUrlOrThrow()
        val event = eventDao.getById(eventId)
            ?: throw IllegalStateException("unregister: unknown event $eventId")
        val bearer = SyncCrypto.computeWorkerBearer(event.encryptionKey, eventId)
        val deviceId = identity.deviceId()
        val request = Request.Builder()
            .url("$baseUrl/events/${enc(eventId)}/devices/${enc(deviceId)}/token")
            .header("authorization", "Bearer $bearer")
            .delete()
            .build()
        execute(request)
    }

    /**
     * Re-registers the current FCM token against every event known
     * locally. Called from [FairShareApp] on startup (so a token that
     * rotated while the app was off still reaches the Worker) and
     * from [FairShareMessagingService.onNewToken].
     */
    suspend fun reRegisterAllEvents(): Result<Unit> = runCatching {
        val token = fetchFcmToken()
        val events = eventDao.observeAll().first()
        for (event in events) {
            if (event.encryptionKey.isEmpty()) continue
            try {
                sendPut(event.id, token)
            } catch (t: Throwable) {
                Log.w("PushTokenRegistrar", "re-register failed for ${event.id}: ${t.message}")
            }
        }
    }

    private suspend fun sendPut(eventId: String, token: String) {
        val baseUrl = baseUrlOrThrow()
        val event = eventDao.getById(eventId)
            ?: throw IllegalStateException("register: unknown event $eventId")
        val bearer = SyncCrypto.computeWorkerBearer(event.encryptionKey, eventId)
        val deviceId = identity.deviceId()
        val body = json.encodeToString(TokenBodyWire.serializer(), TokenBodyWire(token))
        val request = Request.Builder()
            .url("$baseUrl/events/${enc(eventId)}/devices/${enc(deviceId)}/token")
            .header("authorization", "Bearer $bearer")
            .put(body.toRequestBody(JSON_MEDIA))
            .build()
        execute(request)
    }

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

    private suspend fun fetchFcmToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> cont.resume(token) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
