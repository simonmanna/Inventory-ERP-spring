package com.poscafe.pos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.favre.lib.crypto.bcrypt.BCrypt
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.ServerUrlPolicy
import com.poscafe.pos.data.account.Account
import com.poscafe.pos.data.account.AccountException
import com.poscafe.pos.data.account.AccountRepository
import com.poscafe.pos.data.account.SignInStep
import com.poscafe.pos.data.local.dao.RegisterDao
import com.poscafe.pos.data.local.dao.StaffDao
import com.poscafe.pos.data.local.entity.CashRegisterEntity
import com.poscafe.pos.data.local.entity.StaffEntity
import com.poscafe.pos.data.repo.DeviceRevokedException
import com.poscafe.pos.data.repo.SyncRepository
import com.poscafe.pos.sync.SyncWorker
import com.poscafe.pos.ui.components.PosCard
import com.poscafe.pos.ui.components.PrimaryButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * First-run setup. Three paths:
 *  - ACCOUNT (default, mobile app shell 2026-11-23): an administrator signs in with their ERP account and the phone
 *    registers itself (`POST /sync/devices/register`, `organization:update`); the first pull seeds the catalog and
 *    the admin lands on the dashboard, signed in.
 *  - SERVER: classic enrollment with a device id + token copied from the back office.
 *  - STANDALONE: the tablet IS the whole POS. A local admin + register are
 *    created on the spot; no server, no internet, ever.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    val config: DeviceConfig,
    private val sync: SyncRepository,
    private val staffDao: StaffDao,
    private val registerDao: RegisterDao,
    private val account: AccountRepository,
) : ViewModel() {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    val accountForm = AccountFormState(account.lastOrganizationCode.orEmpty(), account.lastEmail.orEmpty())

    /** Step 1 of enrol-by-sign-in: server address + the admin's account. */
    fun enrollWithAccount(serverUrl: String, onDone: () -> Unit) = launchBusy {
        val url = serverUrl.trim().removeSuffix("/") + "/"
        ServerUrlPolicy.problem(url)?.let { throw IllegalArgumentException(it) }
        config.serverUrl = url
        when (val step = account.authenticate(accountForm.organizationCode, accountForm.email, accountForm.password)) {
            is SignInStep.NeedsMfa -> accountForm.mfa = step
            is SignInStep.Tokens -> finishEnrollment(step.account, onDone)
        }
    }

    /** Step 1b: the admin's authenticator code. */
    fun verifyMfa(onDone: () -> Unit) = launchBusy {
        val step = accountForm.mfa ?: return@launchBusy
        finishEnrollment(account.verifyMfa(step, accountForm.mfaCode), onDone)
    }

    fun cancelMfa() {
        accountForm.mfa = null
        accountForm.mfaCode = ""
        error = null
    }

    /** Register this phone as the admin, pull, then open the app as them. */
    private suspend fun finishEnrollment(a: Account, onDone: () -> Unit) {
        val device = try {
            account.registerDevice(a, deviceName())
        } catch (e: AccountException) {
            if (e.status == 403) {
                throw AccountException(
                    "Only an administrator (organization:update) can set up a new device. Ask one to sign in on this " +
                        "phone once — after that everyone signs in with their own account.",
                )
            }
            throw e
        }
        config.deviceId = device.id
        config.deviceToken = device.token
        config.prefix = device.prefix
        config.enrolledOrganizationId = a.organizationId
        try {
            sync.resetForEnrollment()
            sync.pull() // validates the new token + seeds the catalog
        } catch (e: Exception) {
            config.clearEnrollment()
            throw IllegalStateException(
                "The device was registered but the first sync failed: ${e.message ?: e.javaClass.simpleName}. " +
                    "Revoke \"${deviceName()}\" in Settings → Offline devices and try again.",
            )
        }
        config.markRevoked(false)
        config.acknowledgeKeystoreReset()
        account.accept(a)
        accountForm.password = ""
        accountForm.mfaCode = ""
        onDone()
    }

    private fun deviceName(): String {
        val model = listOfNotNull(android.os.Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, android.os.Build.MODEL)
            .distinct().joinToString(" ").ifBlank { "Phone" }
        return "Android · $model"
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: "Setup failed"
            } finally {
                busy = false
            }
        }
    }

    fun enroll(serverUrl: String, deviceId: String, token: String, prefix: String, onDone: () -> Unit) {
        viewModelScope.launch {
            busy = true; error = null
            try {
                val url = serverUrl.trim().removeSuffix("/") + "/"
                ServerUrlPolicy.problem(url)?.let { throw IllegalArgumentException(it) }
                config.serverUrl = url
                config.deviceId = deviceId.trim()
                config.deviceToken = token.trim()
                config.prefix = prefix.trim().ifBlank { "D1" }
                sync.resetForEnrollment()
                sync.pull() // validates the token + seeds the catalog
                config.markRevoked(false)
                config.acknowledgeKeystoreReset()
                onDone()
            } catch (e: DeviceRevokedException) {
                error = "The server rejected this device id or token"
            } catch (e: Exception) {
                error = e.message ?: "Enrollment failed"
                config.clearEnrollment()
            } finally {
                busy = false
            }
        }
    }

    fun setupStandalone(businessName: String, adminName: String, pin: String, prefix: String, onDone: () -> Unit) {
        viewModelScope.launch {
            busy = true; error = null
            try {
                require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN must be 4–8 digits" }
                require(adminName.isNotBlank()) { "Admin name is required" }
                val pinHash = withContext(Dispatchers.Default) { BCrypt.withDefaults().hashToString(10, pin.toCharArray()) }
                staffDao.upsertAll(
                    listOf(
                        StaffEntity(
                            id = UUID.randomUUID().toString(),
                            firstName = adminName.trim(),
                            lastName = null,
                            email = "admin@local",
                            pinHash = pinHash,
                            permissions = "admin",
                            isActive = true,
                        ),
                    ),
                )
                registerDao.upsertAll(
                    listOf(CashRegisterEntity(id = UUID.randomUUID().toString(), code = "REG1", name = "Main register")),
                )
                config.businessName = businessName.trim().ifBlank { "POS CAFE" }
                config.prefix = prefix.trim().ifBlank { "R1" }
                config.standalone = true
                config.markRevoked(false)
                config.acknowledgeKeystoreReset()
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Setup failed"
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun SetupScreen(onDone: (signedIn: Boolean) -> Unit, vm: SetupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    // Signing in is the normal way to set up a phone now; the other two stay one tap away.
    var mode by remember { mutableStateOf("account") }

    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Storefront, null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Set up this device", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (mode == "account") "Sign in with an administrator account to connect this phone to your company."
                else "Run fully offline on this device, or connect to your POS server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            val notice = when {
                vm.config.revoked.collectAsState().value ->
                    "This terminal was revoked in the back office and has been signed out. " +
                        "Any unsynced sales are kept and will upload once a manager re-enrolls it."
                vm.config.keystoreReset ->
                    "This device's secure key store was reset, so local data could not be recovered. " +
                        "Set the terminal up again."
                else -> null
            }
            if (notice != null) {
                Spacer(Modifier.height(16.dp))
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            if (mode == "account") {
                AccountSetupForm(vm) {
                    SyncWorker.schedule(context)
                    onDone(true)
                }
                Spacer(Modifier.height(20.dp))
                Text("Other ways to set up", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mode != "account") {
                    ModeCard(
                        "Sign in", "Admin account\nsets it up.",
                        Icons.Outlined.Storefront, false, Modifier.weight(1f),
                    ) { mode = "account" }
                }
                ModeCard(
                    "Device code", "ID + token from\nthe back office.",
                    Icons.Outlined.CloudSync, mode == "server", Modifier.weight(1f),
                ) { mode = "server" }
                ModeCard(
                    "Standalone", "Works 100% offline.\nNo server needed.",
                    Icons.Outlined.WifiOff, mode == "standalone", Modifier.weight(1f),
                ) { mode = "standalone" }
            }
            Spacer(Modifier.height(16.dp))
            when (mode) {
                "standalone" -> StandaloneForm(vm) { onDone(false) }
                "server" -> ServerForm(vm) {
                    SyncWorker.schedule(context)
                    onDone(false)
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                icon, null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StandaloneForm(vm: SetupViewModel, onDone: () -> Unit) {
    var businessName by remember { mutableStateOf("") }
    var adminName by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("R1") }

    PosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = businessName, onValueChange = { businessName = it },
                label = { Text("Business name") }, singleLine = true,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = adminName, onValueChange = { adminName = it },
                label = { Text("Admin name (first cashier)") }, singleLine = true,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text("Admin PIN (4–8 digits)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = prefix, onValueChange = { prefix = it },
                    label = { Text("Receipt prefix") }, singleLine = true,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.width(140.dp),
                )
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            PrimaryButton(
                text = if (vm.busy) "Setting up…" else "Start selling offline",
                enabled = !vm.busy && businessName.isNotBlank() && adminName.isNotBlank() && pin.length >= 4,
                onClick = { vm.setupStandalone(businessName, adminName, pin, prefix, onDone) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "You can add menu items, staff, customers and stock from the side menu after setup. Cloud sync can be enabled later by re-installing in server mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountSetupForm(vm: SetupViewModel, onEnrolled: () -> Unit) {
    var serverUrl by remember {
        mutableStateOf(vm.config.serverUrl?.removeSuffix("/") ?: "http://192.168.1.10:8080/api/v1")
    }
    PosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.accountForm.mfa == null) {
                OutlinedTextField(
                    value = serverUrl, onValueChange = { serverUrl = it.trim() },
                    label = { Text("Server URL") }, singleLine = true,
                    supportingText = { Text("Your ERP address, ending in /api/v1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
            }
            AccountFields(
                form = vm.accountForm,
                busy = vm.busy,
                error = vm.error,
                submitLabel = "Sign in & set up",
                onSubmit = { vm.enrollWithAccount(serverUrl, onEnrolled) },
                onVerifyMfa = { vm.verifyMfa(onEnrolled) },
                onCancelMfa = vm::cancelMfa,
            )
        }
    }
}

@Composable
private fun ServerForm(vm: SetupViewModel, onEnrolled: () -> Unit) {
    // Re-enrolling keeps the last server address; a fresh device gets an example.
    var serverUrl by remember {
        mutableStateOf(vm.config.serverUrl?.removeSuffix("/") ?: "http://192.168.1.10:8080/api/v1")
    }
    var deviceId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("D1") }

    PosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Register the device in the back office (Settings → Offline devices), then enter the details it shows you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text("Server URL") }, singleLine = true,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = deviceId, onValueChange = { deviceId = it },
                label = { Text("Device ID") }, singleLine = true,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Device token (shown once)") }, singleLine = true,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prefix, onValueChange = { prefix = it },
                label = { Text("Receipt prefix") }, singleLine = true,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.width(180.dp),
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            PrimaryButton(
                text = if (vm.busy) "Enrolling…" else "Enroll & first sync",
                enabled = !vm.busy && serverUrl.isNotBlank() && deviceId.isNotBlank() && token.isNotBlank(),
                onClick = { vm.enroll(serverUrl, deviceId, token, prefix, onEnrolled) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
