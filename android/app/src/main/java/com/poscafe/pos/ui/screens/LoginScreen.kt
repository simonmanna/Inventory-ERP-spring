package com.poscafe.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.account.AccountException
import com.poscafe.pos.data.account.AccountRepository
import com.poscafe.pos.data.account.SignInStep
import com.poscafe.pos.data.repo.AuthRepository
import com.poscafe.pos.ui.components.PosCard
import com.poscafe.pos.ui.components.PrimaryButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the account form holds while someone types (shared by sign-in and device setup). */
class AccountFormState(organizationCode: String, email: String) {
    var organizationCode by mutableStateOf(organizationCode)
    var email by mutableStateOf(email)
    var password by mutableStateOf("")
    var mfaCode by mutableStateOf("")
    var mfa by mutableStateOf<SignInStep.NeedsMfa?>(null)

    val canSubmit: Boolean get() = organizationCode.isNotBlank() && email.isNotBlank() && password.isNotEmpty()
}

/**
 * Sign in to the app (mobile app shell, 2026-11-23): the person's ERP account, like the web — organization code,
 * email, password, then the authenticator code when MFA is on. Offline the account cannot be checked, so the screen
 * offers the staff PIN instead (owner decision: offline falls back to the PIN). A standalone till has no server
 * accounts and signs in with the PIN only.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    val config: DeviceConfig,
    private val account: AccountRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    val form = AccountFormState(account.lastOrganizationCode.orEmpty(), account.lastEmail.orEmpty())
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** The last attempt failed for want of a connection: point at the PIN. */
    var offline by mutableStateOf(false); private set
    var usePin by mutableStateOf(config.standalone)

    fun signIn(onDone: () -> Unit) = run(onDone) {
        when (val step = account.authenticate(form.organizationCode, form.email, form.password)) {
            is SignInStep.NeedsMfa -> { form.mfa = step; false }
            is SignInStep.Tokens -> { account.accept(step.account); true }
        }
    }

    fun verifyMfa(onDone: () -> Unit) = run(onDone) {
        val step = form.mfa ?: return@run false
        account.accept(account.verifyMfa(step, form.mfaCode))
        true
    }

    fun cancelMfa() {
        form.mfa = null
        form.mfaCode = ""
        error = null
    }

    /** The PIN pad signed a cashier in: they open the app too. */
    fun adoptPinUser() {
        auth.cashier?.let { auth.signInApp(it) }
    }

    private fun run(onDone: () -> Unit, block: suspend () -> Boolean) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            offline = false
            try {
                if (block()) {
                    form.password = ""
                    form.mfaCode = ""
                    onDone()
                }
            } catch (e: AccountException) {
                error = e.message
                offline = e.status == null && e.message?.contains("connection", ignoreCase = true) == true
            } catch (e: Exception) {
                error = e.message ?: "Sign-in failed"
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun LoginScreen(onSignedIn: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    if (vm.usePin) {
        Column(Modifier.fillMaxSize()) {
            PinLoginScreen(
                onLoggedIn = { vm.adoptPinUser(); onSignedIn() },
                title = if (vm.config.standalone) "Sign in" else "Sign in with your PIN",
                subtitle = if (vm.config.standalone) "Tap your name, then enter your PIN."
                else "Works without a connection. Tap your name, then enter your PIN.",
                onBack = if (vm.config.standalone) null else ({ vm.usePin = false }),
            )
        }
        return
    }
    AuthBackdrop {
        BrandMark(vm.config.businessName, "Sign in to continue")
        Spacer(Modifier.height(20.dp))
        PosCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountFields(
                    form = vm.form,
                    busy = vm.busy,
                    error = vm.error,
                    submitLabel = "Sign in",
                    onSubmit = { vm.signIn(onSignedIn) },
                    onVerifyMfa = { vm.verifyMfa(onSignedIn) },
                    onCancelMfa = vm::cancelMfa,
                )
                if (vm.offline) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.WifiOff, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "No connection — you can still sign in with your PIN and sell offline.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { vm.usePin = true },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Outlined.Dialpad, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Use PIN instead")
                }
            }
        }
    }
}

/** Organization code, email and password, then the MFA code — the web's sign-in fields. */
@Composable
fun AccountFields(
    form: AccountFormState,
    busy: Boolean,
    error: String?,
    submitLabel: String,
    onSubmit: () -> Unit,
    onVerifyMfa: () -> Unit,
    onCancelMfa: () -> Unit,
) {
    if (form.mfa != null) {
        Text("Two-step verification", style = MaterialTheme.typography.titleMedium)
        Text(
            "Enter the 6-digit code from your authenticator app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = form.mfaCode,
            onValueChange = { form.mfaCode = it.filter(Char::isDigit).take(8) },
            label = { Text("Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (form.mfaCode.length >= 6) onVerifyMfa() }),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        PrimaryButton(if (busy) "Checking…" else "Verify", onVerifyMfa, Modifier.fillMaxWidth(), enabled = !busy && form.mfaCode.length >= 6)
        TextButton(onClick = onCancelMfa, enabled = !busy) { Text("Back") }
        return
    }
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = form.organizationCode,
        onValueChange = { form.organizationCode = it.trim() },
        label = { Text("Organization code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.email,
        onValueChange = { form.email = it.trim() },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.password,
        onValueChange = { form.password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (form.canSubmit && !busy) onSubmit() }),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPassword) "Hide password" else "Show password")
            }
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    PrimaryButton(if (busy) "Signing in…" else submitLabel, onSubmit, Modifier.fillMaxWidth(), enabled = !busy && form.canSubmit)
}

/** The sign-in screens' backdrop: a brand-green wash at the top, the form centred and scrollable above the keyboard. */
@Composable
fun AuthBackdrop(content: @Composable () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxWidth().height(260.dp).background(
                Brush.verticalGradient(listOf(primary.copy(alpha = 0.22f), Color.Transparent)),
            ),
        )
        Box(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 460.dp).padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                content()
            }
        }
    }
}

@Composable
fun BrandMark(title: String, subtitle: String) {
    Box(
        Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Storefront, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
    }
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
