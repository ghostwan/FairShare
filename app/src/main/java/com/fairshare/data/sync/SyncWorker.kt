package com.fairshare.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Deferred-retry wrapper around [SyncCoordinator]. The coordinator
 * runs synchronously when the app is in the foreground (called from
 * the relevant ViewModels), but a push that hits a transient network
 * error there must not be lost: this Worker re-runs the sync with an
 * exponential backoff until the device is online again.
 *
 * Constraints: requires [NetworkType.CONNECTED] so WorkManager only
 * dispatches us when the OS thinks the network is up. Backoff is
 * exponential with a 15-second base — the OS will eventually stretch
 * to roughly 1 hour.
 *
 * Scope: pass an optional `KEY_EVENT_ID` Data to sync a single event;
 * omitting it cycles through every event with an encryption key.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: SyncCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val eventId = inputData.getString(KEY_EVENT_ID)
        return try {
            if (eventId != null) {
                coordinator.syncEvent(eventId).fold(
                    onSuccess = { Result.success() },
                    onFailure = { onTransient(it) },
                )
            } else {
                val outcomes = coordinator.syncAllEvents()
                if (outcomes.any { it.isFailure }) {
                    Log.w("SyncWorker", "Partial failure: ${outcomes.count { it.isFailure }}/${outcomes.size}")
                    Result.retry()
                } else {
                    Result.success()
                }
            }
        } catch (t: Throwable) {
            onTransient(t)
        }
    }

    private fun onTransient(t: Throwable): Result {
        Log.w("SyncWorker", "Sync failed, will retry", t)
        return Result.retry()
    }

    companion object {
        const val KEY_EVENT_ID = "eventId"
        private const val UNIQUE_NAME_ALL = "fairshare-sync-all"
        private const val UNIQUE_NAME_PREFIX = "fairshare-sync-"

        /**
         * Enqueues a one-shot sync, replacing any pending request for
         * the same scope so we don't pile up duplicates when the user
         * pulls-to-refresh repeatedly.
         */
        fun enqueueOneShot(context: Context, eventId: String? = null) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .apply {
                    if (eventId != null) {
                        setInputData(Data.Builder().putString(KEY_EVENT_ID, eventId).build())
                    }
                }
                .build()
            val name = eventId?.let { UNIQUE_NAME_PREFIX + it } ?: UNIQUE_NAME_ALL
            WorkManager.getInstance(context)
                .enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
