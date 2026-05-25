package com.fairshare

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.fairshare.data.sync.PushTokenRegistrar
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FairShareApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var pushRegistrar: PushTokenRegistrar

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Catch up the Worker with our current FCM token across every
        // event known locally. FCM tokens can rotate while the app is
        // off (after a restore, after Play Services updates, …) and
        // FirebaseMessagingService.onNewToken does not fire for past
        // rotations — so we always sync on startup. Best-effort: any
        // failure is logged and the polling fallback covers us.
        appScope.launch {
            pushRegistrar.reRegisterAllEvents().onFailure {
                Log.w("FairShareApp", "FCM re-register on startup failed: ${it.message}")
            }
        }
    }
}
