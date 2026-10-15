package com.example.tefal2garmin.health

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background Health Connect -> Garmin sync (task 2b). Runs the exact same
 * [SyncEngine] pass as the manual "Sync now" button, but enqueued by
 * WorkManager every ~3 hours while a network connection exists.
 *
 * Two entry points by design (documented split):
 *  - FOREGROUND/manual: MainViewModel calls the engine directly through
 *    [HealthSyncFacade] so the UI gets instant state (running/success/error).
 *  - BACKGROUND: only this worker — WorkManager owns retries, network
 *    constraints and process death. The worker never touches UI state.
 *
 * Outcome mapping ([mapSyncResult]): every terminal outcome is a success for
 * WorkManager — Idle/Uploaded/PartialUploaded are done deals, and
 * LoginRequired simply leaves the Connect Garmin entry point visible the next
 * time the UI opens. Only Retryable asks WorkManager to back off and retry.
 *
 * Background reads: on providers where READ_HEALTH_DATA_IN_BACKGROUND cannot
 * be granted (Android 14 or an old Health Connect apk) the read calls throw
 * SecurityException mid-pass. That is a degradation, not a failure — the pass
 * is dropped with success and manual foreground sync keeps working.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        val facade = HealthSyncWiring.facade(applicationContext)
        return try {
            mapSyncResult(facade.sync())
        } catch (e: SecurityException) {
            // Permissions revoked while the pass was running (or background
            // reads unsupported): degrade quietly, never crash the process.
            Log.w(TAG, "Health Connect permissions revoked mid-run; dropping this pass", e)
            ListenableWorker.Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Health Connect sync pass failed; retrying via backoff", e)
            ListenableWorker.Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"

        /** Unique periodic work name ("hc-sync"). */
        const val UNIQUE_WORK_NAME = "hc-sync"

        /**
         * Enqueues the ~3h periodic sync (network-constrained, exponential
         * backoff). KEEP: an already-scheduled request is never re-created,
         * so calling this from every MainActivity.onCreate is idempotent.
         */
        fun enqueuePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Pure [SyncResult] to WorkManager-outcome mapping, kept separate from
         * [doWork] so it is JVM-unit-testable without the work-testing
         * artifact.
         */
        fun mapSyncResult(result: SyncResult): ListenableWorker.Result = when (result) {
            SyncResult.Retryable -> ListenableWorker.Result.retry()
            SyncResult.Idle -> ListenableWorker.Result.success()
            is SyncResult.Uploaded -> ListenableWorker.Result.success()
            is SyncResult.PartialUploaded -> ListenableWorker.Result.success()
            SyncResult.LoginRequired -> ListenableWorker.Result.success()
        }
    }
}
