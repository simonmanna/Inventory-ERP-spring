package com.poscafe.pos.data.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.poscafe.pos.data.DeviceConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Daily automatic backup into the folder chosen on the Backup screen. No network needed. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupRepository,
    private val config: DeviceConfig,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!config.autoBackup || config.backupFolderUri == null || config.backupPassphrase == null) return Result.success()
        // Already backed up within the day (e.g. by hand): skip.
        if (System.currentTimeMillis() - config.lastBackupAt < TimeUnit.HOURS.toMillis(20)) return Result.success()
        return try {
            backups.backupToFolder()
            Result.success()
        } catch (e: Exception) {
            Log.w("BackupWorker", "automatic backup failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "pos-backup-daily"

        /** Enable or disable the daily run to match the setting. */
        fun apply(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            wm.enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS).build(),
            )
        }
    }
}
