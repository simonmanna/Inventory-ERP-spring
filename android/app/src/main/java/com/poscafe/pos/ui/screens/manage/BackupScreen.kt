package com.poscafe.pos.ui.screens.manage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.backup.BackupRepository
import com.poscafe.pos.data.backup.BackupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupRepository,
    val config: DeviceConfig,
) : ViewModel() {

    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var unsynced by mutableStateOf(0); private set
    var lastBackupAt by mutableStateOf(config.lastBackupAt); private set
    var hasPassphrase by mutableStateOf(config.backupPassphrase != null); private set
    var folder by mutableStateOf(config.backupFolderUri); private set
    var auto by mutableStateOf(config.autoBackup); private set

    /** Restore picked and passphrase checked: waiting for the owner's go-ahead. */
    var pending by mutableStateOf<BackupRepository.Meta?>(null); private set
    private var pendingPass: String? = null

    init {
        refresh()
        // A failed automatic backup is reported here until the next success.
        error = config.lastBackupError
    }

    fun refresh() {
        viewModelScope.launch { unsynced = backups.unsyncedCount() }
        lastBackupAt = config.lastBackupAt
    }

    fun suggestedName() = backups.fileName()

    fun setPassphrase(p: String) {
        config.backupPassphrase = p
        hasPassphrase = true
        message = "Backup passphrase saved. Write it down: a backup cannot be opened without it."
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        message = null
        viewModelScope.launch {
            try { block() } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName } finally { busy = null; refresh() }
        }
    }

    fun backupTo(uri: Uri) = run("Backing up…") {
        val m = backups.backupTo(uri)
        message = "Backed up ${m.sales} sales and all settings."
    }

    fun chooseFolder(tree: Uri) {
        runCatching { backups.rememberFolder(tree) }
            .onSuccess { folder = config.backupFolderUri; toggleAuto(true) }
            .onFailure { error = "Could not keep access to that folder: ${it.message}" }
    }

    fun toggleAuto(on: Boolean) {
        config.autoBackup = on
        auto = on
        BackupWorker.apply(context, on)
    }

    fun backupToFolderNow() = run("Backing up…") {
        val m = backups.backupToFolder()
        message = "Saved to the backup folder (${m.sales} sales). The newest 7 backups are kept."
    }

    fun inspect(uri: Uri, pass: String) = run("Checking backup…") {
        pending = backups.inspect(uri, pass)
        pendingPass = pass
    }

    fun cancelRestore() { pending = null; pendingPass = null }

    fun restore(onRestored: () -> Unit) {
        val meta = pending ?: return
        val pass = pendingPass ?: return
        run("Restoring…") {
            backups.restoreStaged(pass, meta)
            onRestored()
        }
    }
}

private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.MIME)) { uri ->
        uri?.let(vm::backupTo)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::chooseFolder)
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = uri
    }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { vm.refresh() }
    // Results print at the top; bring them into view wherever the button was.
    LaunchedEffect(vm.message, vm.error) { if (vm.message != null || vm.error != null) scroll.animateScrollTo(0) }

    ManageScaffold(title = "Backup & Restore", onBack = onBack) { _ ->
        Column(
            Modifier.verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(vm)

            vm.busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            vm.message?.let { Note(it, MaterialTheme.colorScheme.primary) }
            vm.error?.let { Note(it, MaterialTheme.colorScheme.error) }

            PassphraseCard(vm)

            Card(Icons.Outlined.Backup, "Back up now", "Save one encrypted file with every sale, shift, product, customer and setting. Put it on Google Drive, a memory card or another phone.") {
                Button(
                    onClick = { saveFile.launch(vm.suggestedName()) },
                    enabled = vm.hasPassphrase && vm.busy == null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Save backup file…") }
                if (!vm.hasPassphrase) Hint("Set a backup passphrase first.")
            }

            Card(Icons.Outlined.Folder, "Automatic daily backup", "Once a day the till saves a backup into a folder you choose and keeps the newest 7. Pick a synced folder (e.g. Google Drive) so it survives a lost phone.") {
                Text(
                    vm.folder?.let { "Folder: " + folderLabel(it) } ?: "No folder chosen",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Back up every day", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = vm.auto && vm.folder != null, onCheckedChange = vm::toggleAuto, enabled = vm.folder != null && vm.hasPassphrase)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = vm.hasPassphrase, modifier = Modifier.weight(1f)) {
                        Text(if (vm.folder == null) "Choose folder" else "Change folder")
                    }
                    OutlinedButton(onClick = vm::backupToFolderNow, enabled = vm.folder != null && vm.hasPassphrase && vm.busy == null, modifier = Modifier.weight(1f)) {
                        Text("Run now")
                    }
                }
            }

            Card(Icons.Outlined.Restore, "Restore from backup", "Replaces everything on this phone with the backup: sales, shifts, catalog, staff and receipt settings. Use it to move to a new phone or recover a lost one.") {
                OutlinedButton(
                    onClick = { pickBackup.launch(arrayOf("*/*")) },
                    enabled = vm.busy == null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Choose backup file…") }
            }
        }
    }

    restoreUri?.let { uri ->
        PassphraseDialog(
            initial = vm.config.backupPassphrase ?: "",
            onDismiss = { restoreUri = null },
            onOk = { pass -> vm.inspect(uri, pass); restoreUri = null },
        )
    }
    vm.pending?.let { meta ->
        ConfirmRestore(meta, vm.unsynced, vm.config.isEnrolled, vm.busy != null, onCancel = vm::cancelRestore) {
            vm.restore { restartApp(context) }
        }
    }
}

/** Relaunch on the restored database: every singleton still holds the closed one. */
private fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}

private fun folderLabel(uri: String): String =
    Uri.decode(Uri.parse(uri).lastPathSegment ?: uri).substringAfter(':').ifBlank { "storage root" }

@Composable
private fun StatusCard(vm: BackupViewModel) {
    val last = vm.lastBackupAt
    val ageH = if (last == 0L) Long.MAX_VALUE else (System.currentTimeMillis() - last) / 3_600_000
    val (color, title) = when {
        last == 0L -> MaterialTheme.colorScheme.error to "Never backed up"
        ageH < 26 -> Color(0xFF10A06C) to "Backed up"
        ageH < 72 -> Color(0xFFD97706) to "Backup is ${ageH / 24} day${if (ageH / 24 == 1L) "" else "s"} old"
        else -> MaterialTheme.colorScheme.error to "Backup is ${ageH / 24} days old"
    }
    Surface(shape = RoundedCornerShape(24.dp), color = color.copy(alpha = 0.10f), border = BorderStroke(1.dp, color.copy(alpha = 0.4f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Icon(if (last != 0L && ageH < 26) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                    Text(
                        if (last == 0L) "Everything lives only on this phone." else Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).format(WHEN),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (vm.config.standalone) {
                    "This till runs on its own: no server keeps a copy. Uninstalling the app or losing the phone deletes all sales unless you have a backup."
                } else {
                    "Synced sales are also on the server. A backup still keeps what has not synced yet, plus local settings."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (vm.unsynced > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${vm.unsynced} change${if (vm.unsynced == 1) "" else "s"} not synced yet", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PassphraseCard(vm: BackupViewModel) {
    var editing by remember { mutableStateOf(!vm.hasPassphrase) }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    Card(Icons.Outlined.Key, "Backup passphrase", "Encrypts every backup. You need it to restore on any phone; nobody can recover it for you.") {
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF10A06C), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Passphrase set", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = true }) { Text("Change") }
            }
            return@Card
        }
        OutlinedTextField(
            value = a, onValueChange = { a = it }, label = { Text("Passphrase (8+ characters)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = b, onValueChange = { b = it }, label = { Text("Repeat passphrase") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = b.isNotEmpty() && a != b,
            modifier = Modifier.fillMaxWidth(),
        )
        if (vm.hasPassphrase) Hint("Older backups keep their old passphrase.")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.hasPassphrase) OutlinedButton(onClick = { editing = false; a = ""; b = "" }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = { vm.setPassphrase(a); editing = false; a = ""; b = "" },
                enabled = a.length >= 8 && a == b,
                modifier = Modifier.weight(1f),
            ) { Text("Save passphrase") }
        }
    }
}

@Composable
private fun PassphraseDialog(initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var p by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup passphrase") },
        text = {
            Column {
                Text("Enter the passphrase this backup was made with.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = p, onValueChange = { p = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(p) }, enabled = p.isNotBlank()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmRestore(
    meta: BackupRepository.Meta,
    unsynced: Int,
    enrolled: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        icon = { Icon(Icons.Outlined.Restore, null) },
        title = { Text("Restore this backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(meta.businessName.ifBlank { "POS backup" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Made ${Instant.ofEpochMilli(meta.createdAt).atZone(ZoneId.systemDefault()).format(WHEN)}", style = MaterialTheme.typography.bodySmall)
                Text("${meta.sales} sales · ${if (meta.standalone) "standalone till" else "synced till"} · receipts ${meta.prefix}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Everything on this phone is replaced. The app restarts when done." +
                        if (meta.standalone || enrolled) "" else " This phone is not enrolled: enrol it again afterwards to sync.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (unsynced > 0) {
                    Text(
                        "$unsynced change${if (unsynced == 1) "" else "s"} on this phone have not synced and will be lost.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRestore,
                enabled = understood && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(if (busy) "Restoring…" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun Card(icon: ImageVector, title: String, body: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(14.dp), color = color.copy(alpha = 0.10f)) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(12.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
