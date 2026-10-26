package com.poscafe.pos.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.repo.DeviceRevokedException
import com.poscafe.pos.data.repo.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background sync: push the op queue first (money before catalog), then pull
 * incremental catalog changes. Scheduled three ways:
 *   - periodic (15 min, network-required),
 *   - one-shot on connectivity regain / app foreground,
 *   - manual from the sync screen.
 * Periodic and one-shot runs have separate unique names and may overlap;
 * SyncRepository serialises them with a mutex.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: SyncRepository,
    private val config: DeviceConfig,
    private val status: SyncStatus,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!config.isEnrolled) return Result.success()
        return try {
            sync.pushAll()
            sync.pull()
            status.lastError.value = null
            Result.success()
        } catch (e: DeviceRevokedException) {
            // Already wiped back to setup; nothing left to retry.
            status.lastError.value = e.message
            Result.success()
        } catch (e: Exception) {
            Log.w("SyncWorker", "sync failed, will retry", e)
            status.lastError.value = e.message ?: e.javaClass.simpleName
            // Connectivity or server trouble — retry with backoff. Failed OPS
            // (server rejections) are already parked locally + dead-lettered
            // server-side; retry only re-attempts queued ones.
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC = "pos-sync-periodic"
        const val ONESHOT = "pos-sync-now"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build(),
            )
        }

        /**
         * @param replace true (manual / reconnect): cancel any pending run,
         *   including one parked in retry backoff, and go now. false (app
         *   foreground, fires on every rotation): leave an existing run alone
         *   rather than cancel an in-flight push mid-request.
         */
        fun syncNow(context: Context, replace: Boolean = true) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build(),
            )
        }
    }
}
