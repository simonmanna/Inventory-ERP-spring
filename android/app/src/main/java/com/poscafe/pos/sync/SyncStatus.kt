package com.poscafe.pos.sync

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.dao.SyncStateDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** One glanceable snapshot of the device's sync health, for banners and the Sync screen. */
data class SyncSnapshot(
    val standalone: Boolean = false,
    val online: Boolean = true,
    val running: Boolean = false,
    val queued: Int = 0,
    val failed: Int = 0,
    /** Epoch millis of the most recent successful push or pull; null = never. */
    val lastSyncAt: Long? = null,
    /** Why the last run failed (network/server), cleared on success. In-memory only. */
    val lastError: String? = null,
)

/**
 * Combines connectivity, the op queue, WorkManager state and the sync cursor
 * into a single [SyncSnapshot] stream. Read-only: triggering stays with
 * [SyncWorker].
 */
@Singleton
class SyncStatus @Inject constructor(
    @ApplicationContext context: Context,
    private val config: DeviceConfig,
    private val connectivity: ConnectivityMonitor,
    private val opQueue: OpQueueDao,
    private val syncState: SyncStateDao,
) {
    private val work = WorkManager.getInstance(context)

    /** Written by [SyncWorker]; same process, so a plain state flow is enough. */
    val lastError = MutableStateFlow<String?>(null)

    private val running: Flow<Boolean> = combine(
        work.getWorkInfosForUniqueWorkFlow(SyncWorker.ONESHOT),
        work.getWorkInfosForUniqueWorkFlow(SyncWorker.PERIODIC),
    ) { a, b -> (a + b).any { it.state == WorkInfo.State.RUNNING } }

    val snapshot: Flow<SyncSnapshot> =
        if (config.standalone) {
            flowOf(SyncSnapshot(standalone = true))
        } else {
            combine(
                combine(connectivity.online, running, ::Pair),
                opQueue.queuedCount(),
                opQueue.failedCount(),
                syncState.observe().map { s -> listOfNotNull(s?.lastPullAt, s?.lastPushAt).maxOrNull() },
                lastError,
            ) { (online, run), queued, failed, last, err ->
                SyncSnapshot(false, online, run, queued, failed, last, err)
            }.distinctUntilChanged()
        }
}
