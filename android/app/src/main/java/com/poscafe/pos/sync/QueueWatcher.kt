package com.poscafe.pos.sync

import android.content.Context
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.dao.OpQueueDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Pushes new work soon after it is queued instead of waiting up to 15 min for
 * the periodic run: the back office sees sales within seconds, and less is
 * at risk if the till is lost or the app is uninstalled. The request goes to
 * WorkManager at once (no in-memory debounce), so it survives the process
 * being killed right after a sale. Bursts still batch: a run already queued
 * is kept, and the worker pushes until the queue is empty. Offline, the job
 * waits for its network constraint.
 */
@Singleton
class QueueWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: DeviceConfig,
    // Provider: don't open the encrypted database during Application.onCreate.
    private val opQueue: Provider<OpQueueDao>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** Idempotent; call once from Application.onCreate. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            opQueue.get().queuedCount()
                // Emit only when the count goes up (something new was queued).
                .runningFold(Pair(Int.MAX_VALUE, 0)) { (_, last), now -> last to now }
                .filter { (before, now) -> now > before }
                .collect { if (config.isEnrolled) SyncWorker.syncNow(context, replace = false) }
        }
    }
}
