package com.fairshare.data.sync

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives FCM data messages from the sync Worker.
 *
 * The Worker sends a data-only message with `data.eventId = <id>`
 * (see `worker/src/fcm.ts` `fcmFanOut`) whenever a peer pushes new
 * ops for an event this device has registered for. We translate that
 * into a one-shot [SyncWorker] job so the pull happens under standard
 * WorkManager constraints (network connected, backoff on failure).
 *
 * Data-only messages are delivered to [onMessageReceived] in both
 * foreground and background, which is exactly what we want — no
 * notification surfaces to the user; the sync is silent.
 *
 * Token rotation: FCM occasionally rotates a device's registration
 * token (after a restore, after clearing app data on a paired device,
 * etc.). [onNewToken] fan-outs the new token to every event we're
 * paired with so the Worker stops pushing to a dead token.
 */
@AndroidEntryPoint
class FairShareMessagingService : FirebaseMessagingService() {

    @Inject lateinit var registrar: PushTokenRegistrar

    /**
     * onMessageReceived runs on a background thread; onNewToken does
     * too. We still need a coroutine scope for the suspend network
     * calls in [PushTokenRegistrar]. A SupervisorJob keeps a failure
     * in one event from cancelling re-registration for the others.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val eventId = message.data["eventId"]
        if (eventId.isNullOrEmpty()) {
            Log.w("FairShareFCM", "Received push without eventId; ignoring")
            return
        }
        SyncWorker.enqueueOneShot(applicationContext, eventId)
    }

    override fun onNewToken(token: String) {
        scope.launch {
            registrar.reRegisterAllEvents().onFailure { t ->
                Log.w("FairShareFCM", "Re-register on new token failed: ${t.message}")
            }
        }
    }
}
