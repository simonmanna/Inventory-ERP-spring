package com.poscafe.pos.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.poscafe.pos.data.DeviceConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live "is there a network" signal for the UI, plus the connectivity-regain
 * sync trigger promised in [SyncWorker]'s doc.
 *
 * Deliberately checks only NET_CAPABILITY_INTERNET, not VALIDATED: a LAN-only
 * server (router with no uplink) never passes Android's internet validation,
 * yet the till can still reach it. Whether the *server* answers is the sync
 * worker's business; this only says whether trying is worthwhile.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: DeviceConfig,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var started = false

    /** Idempotent; call once from Application.onCreate. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
        })
    }

    private fun update() {
        val now = currentlyOnline()
        val was = _online.value
        _online.value = now
        // Back online: flush the queue right away instead of waiting up to
        // 15 min for the periodic run (or a long retry backoff).
        if (now && !was && config.isEnrolled) SyncWorker.syncNow(context)
    }

    private fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
