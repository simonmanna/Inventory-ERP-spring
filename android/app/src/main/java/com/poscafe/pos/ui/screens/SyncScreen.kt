package com.poscafe.pos.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.entity.OpQueueEntity
import com.poscafe.pos.sync.SyncSnapshot
import com.poscafe.pos.sync.SyncStatus
import com.poscafe.pos.sync.SyncWorker
import com.poscafe.pos.ui.components.PosCard
import com.poscafe.pos.ui.components.PrimaryButton
import com.poscafe.pos.ui.components.StatCard
import com.poscafe.pos.ui.components.StatusDot
import com.poscafe.pos.ui.components.StatusPill
import com.poscafe.pos.ui.components.relativeTime
import com.poscafe.pos.ui.theme.PosColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sync status + failed-op review. Mirrors the web's failed-sales dialog and
 * the server's dead-letter philosophy: a rejected money op stays visible
 * until a human retries or discards it.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val opQueue: OpQueueDao,
    status: SyncStatus,
) : ViewModel() {
    val snapshot = status.snapshot.stateIn(viewModelScope, SharingStarted.Eagerly, SyncSnapshot())
    val failed = opQueue.failed().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<OpQueueEntity>())

    fun retry(opId: String) = viewModelScope.launch { opQueue.retry(opId) }
    fun retryAll() = viewModelScope.launch { opQueue.retryAllFailed() }
}

@Composable
fun SyncScreen(onBack: () -> Unit, vm: SyncViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()

    // Re-render "x min ago" once a minute without any data change.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text("Sync", style = MaterialTheme.typography.headlineSmall)
        }

        ConnectionCard(s, tick)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("${s.queued}", "Waiting to push", Modifier.weight(1f))
            StatCard(
                "${s.failed}", "Rejected",
                Modifier.weight(1f),
                accent = if (s.failed > 0) MaterialTheme.colorScheme.error else null,
            )
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = when {
                s.running -> "Syncing…"
                !s.online -> "Sync when back online"
                else -> "Sync now"
            },
            enabled = !s.running,
            onClick = { SyncWorker.syncNow(context) },
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
        )
        if (s.running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
        if (failed.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.CloudDone, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nothing needs attention",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Rejected by server — review each one",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (failed.size > 1) {
                    TextButton(onClick = { vm.retryAll(); SyncWorker.syncNow(context) }) { Text("Retry all") }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(failed, key = { it.opId }) { op ->
                    PosCard(Modifier.fillMaxWidth().animateItem()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(op.type, style = MaterialTheme.typography.titleSmall)
                                StatusPill(
                                    "seq ${op.deviceSeq}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                            }
                            Text(
                                op.lastError ?: "Rejected",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { vm.retry(op.opId); SyncWorker.syncNow(context) }) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}

/** Online/offline dot + last successful contact with the server. */
@Composable
private fun ConnectionCard(s: SyncSnapshot, now: Long) {
    val dot by animateColorAsState(
        when {
            !s.online -> PosColors.Warning
            s.running -> PosColors.Info
            else -> PosColors.Green
        },
        tween(250),
        label = "syncDot",
    )
    PosCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(dot, size = 12.dp)
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = when {
                        !s.online -> "Offline — sales are stored on this device"
                        s.running -> "Syncing with server…"
                        else -> "Online"
                    },
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "syncTitle",
                ) { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(
                    "Last synced ${relativeTime(s.lastSyncAt, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.online && !s.running && s.lastError != null) {
                    Text(
                        "Last attempt failed: ${s.lastError} — retrying automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
