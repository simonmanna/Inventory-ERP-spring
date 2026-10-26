package com.poscafe.pos.ui.screens

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.CrashLog
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.entity.OpQueueEntity
import com.poscafe.pos.data.repo.AuthRepository
import com.poscafe.pos.data.repo.SyncRepository
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The app crashed since the last time someone looked: share the report with support, then dismiss it. */
@Composable
private fun CrashCard(report: CrashLog.Report, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Spacer(Modifier.height(12.dp))
    PosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("The app crashed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(
                "${DateUtils.getRelativeTimeSpanString(report.at)} · ${report.summary}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "POS crash report")
                        .putExtra(Intent.EXTRA_TEXT, report.text)
                    context.startActivity(Intent.createChooser(send, "Share crash report"))
                }) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/**
 * Sync status + failed-op review. Mirrors the web's failed-sales dialog and
 * the server's dead-letter philosophy: a rejected money op stays visible
 * until a human retries or discards it.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val opQueue: OpQueueDao,
    private val auth: AuthRepository,
    private val sync: SyncRepository,
    status: SyncStatus,
) : ViewModel() {
    companion object {
        /** Same permission the terminal uses for manager overrides. */
        const val DISCARD_PERMISSION = "pos:override"
    }

    val snapshot = status.snapshot.stateIn(viewModelScope, SharingStarted.Eagerly, SyncSnapshot())
    val failed = opQueue.failed().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<OpQueueEntity>())

    /** Most recent on-device crash report, if any (see [CrashLog]). */
    var crash by mutableStateOf<CrashLog.Report?>(null); private set

    init { viewModelScope.launch(Dispatchers.IO) { crash = CrashLog.latest(context) } }

    fun dismissCrash() {
        crash = null
        viewModelScope.launch(Dispatchers.IO) { CrashLog.clear(context) }
    }

    // Sync only after the ops are requeued, or the push could run first and find nothing.
    fun retry(opId: String) = viewModelScope.launch { sync.retryRejected(opId); SyncWorker.syncNow(context) }
    fun retryAll() = viewModelScope.launch { sync.retryRejected(); SyncWorker.syncNow(context) }

    var discardError by mutableStateOf<String?>(null); private set
    var discardBusy by mutableStateOf(false); private set

    fun clearDiscardError() { discardError = null }

    /** Manager-approved discard of a rejected op; [onDone] runs only on success. */
    fun discard(opId: String, pin: String, onDone: () -> Unit) = viewModelScope.launch {
        discardBusy = true; discardError = null
        try {
            val mgr = auth.verifyPinFor(pin, DISCARD_PERMISSION).getOrNull()
            when {
                mgr == null -> discardError = "Manager PIN with $DISCARD_PERMISSION required"
                !sync.discardRejected(opId, mgr.displayName) -> discardError = "This op is no longer rejected"
                else -> onDone()
            }
        } finally {
            discardBusy = false
        }
    }
}

@Composable
fun SyncScreen(onBack: () -> Unit, vm: SyncViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    var discarding by remember { mutableStateOf<OpQueueEntity?>(null) }
    discarding?.let { op ->
        DiscardDialog(
            op = op,
            busy = vm.discardBusy,
            error = vm.discardError,
            onConfirm = { pin -> vm.discard(op.opId, pin) { discarding = null } },
            onDismiss = { discarding = null; vm.clearDiscardError() },
        )
    }

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
        vm.crash?.let { report -> CrashCard(report, onDismiss = vm::dismissCrash) }
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
                    TextButton(onClick = { vm.retryAll() }) { Text("Retry all") }
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { vm.retry(op.opId) }) { Text("Retry") }
                                TextButton(onClick = { discarding = op }) {
                                    Text("Discard…", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Discarding is final: the server already refused this op and keeps a
 * dead-letter copy; the till stops resending it. A sale must be re-rung if
 * the money was taken.
 */
@Composable
private fun DiscardDialog(
    op: OpQueueEntity,
    busy: Boolean,
    error: String?,
    onConfirm: (pin: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Discard ${op.type}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The server rejected this and will not accept it again. Discarding stops the till " +
                        "resending it and removes it from shift totals. Re-ring a sale if the money was taken.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8) },
                    label = { Text("Manager PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = !busy && pin.length >= 4) {
                Text("Discard", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
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
