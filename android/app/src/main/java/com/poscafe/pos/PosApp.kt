package com.poscafe.pos

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.poscafe.pos.sync.ConnectivityMonitor
import com.poscafe.pos.sync.QueueWatcher
import com.poscafe.pos.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PosApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var connectivity: ConnectivityMonitor
    @Inject lateinit var queueWatcher: QueueWatcher
    @Inject lateinit var config: com.poscafe.pos.data.DeviceConfig

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        SyncWorker.schedule(this)
        com.poscafe.pos.data.backup.BackupWorker.apply(this, config.autoBackup)
        connectivity.start()
        queueWatcher.start()
    }
}
