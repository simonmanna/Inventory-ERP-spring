package com.poscafe.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.account.AccountRepository
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.dao.SaleDao
import com.poscafe.pos.data.local.dao.SettingsDao
import com.poscafe.pos.data.repo.AuthRepository
import com.poscafe.pos.data.repo.CashSessionRepository
import com.poscafe.pos.ui.components.Money
import com.poscafe.pos.ui.components.SyncBanner
import com.poscafe.pos.ui.components.pressScale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

// ---------------------------------------------------------------------------
// Model — the apps on the home screen, filtered by what the signed-in person may open.
// ---------------------------------------------------------------------------

/** Where an app tile goes. */
sealed interface AppTarget {
    /** The POS terminal (PIN-gated) on one of its tabs. */
    data class Pos(val tab: HomeTab) : AppTarget

    /** A screen in the app NavHost. */
    data class Route(val route: String) : AppTarget
}

/** One tile on the dashboard. [need]: any one of these permissions opens it; null = everyone signed in. */
data class DashboardApp(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val target: AppTarget,
    val need: Set<String>? = null,
)

private fun app(id: String, label: String, icon: ImageVector, color: Long, target: AppTarget, vararg need: String) =
    DashboardApp(id, label, icon, Color(color), target, need.toSet().ifEmpty { null })

/**
 * Every app the phone has, in home-screen order (sell first, then operations, then setup), limited by mode — no
 * tables or menu on a retail till, no online-only apps standalone — and by the person's permissions, with the same
 * rules as the POS side drawer ([allowed]).
 */
internal fun dashboardApps(permissions: Set<String>, standalone: Boolean, isRetail: Boolean): List<DashboardApp> = listOfNotNull(
    app("pos", "Point of Sale", Icons.Outlined.Storefront, 0xFF0E9F6E, AppTarget.Pos(HomeTab.Sell)),
    if (isRetail) null else app("tables", "Tables", Icons.Outlined.TableRestaurant, 0xFF14B8A6, AppTarget.Pos(HomeTab.Tables)),
    app("orders", "Orders", Icons.Outlined.Receipt, 0xFF3B82F6, AppTarget.Pos(HomeTab.Orders)),
    app("barcode", "Barcode", Icons.Outlined.QrCodeScanner, 0xFF111827, AppTarget.Route("barcode"), "product:read", "product:update", "product:create", "inventory:read", "pos:checkout"),
    app("products", "Products", Icons.Outlined.Inventory2, 0xFF8B5CF6, AppTarget.Route("products"), "product:read", "product:update"),
    if (isRetail) null else app("menu", "Menu", Icons.Outlined.LocalCafe, 0xFFB45309, AppTarget.Route("menu-manager"), "product:update", "product:create"),
    app("stock", "Inventory", Icons.Outlined.Inventory2, 0xFFF97316, AppTarget.Route("stock"), "inventory:read"),
    app("count", "Stock Count", Icons.Outlined.Checklist, 0xFFEA580C, AppTarget.Route("inventory-count"), "inventory_count:count"),
    app("purchases", "Purchases", Icons.Outlined.ShoppingCart, 0xFF0EA5E9, AppTarget.Route("purchases"), "purchase_order:receive", "inventory_doc:create"),
    app("suppliers", "Suppliers", Icons.Outlined.Business, 0xFF0369A1, AppTarget.Route("suppliers"), "supplier:read", "supplier:manage"),
    app("customers", "Customers", Icons.Outlined.People, 0xFFEC4899, AppTarget.Route("customers"), "partner:read"),
    if (standalone) null else app("crm", "CRM", Icons.Outlined.Handshake, 0xFFDB2777, AppTarget.Route("crm"), *CRM_PERMISSIONS),
    app("expenses", "Expenses", Icons.Outlined.Description, 0xFFDC2626, AppTarget.Route("expenses"), "expense:create", "expense:read"),
    app("shifts", "Shifts", Icons.AutoMirrored.Outlined.ReceiptLong, 0xFF64748B, AppTarget.Route("shift-history"), "cash_session:read", "pos:reports"),
    app("registers", "Registers", Icons.Outlined.PointOfSale, 0xFF475569, AppTarget.Route("registers"), "cash_register:update", "cash_register:create"),
    app("reports", "Reports", Icons.Outlined.Insights, 0xFF6366F1, AppTarget.Route("reports"), "pos:reports"),
    app("pos-report", "POS Report", Icons.Outlined.Insights, 0xFF4F46E5, AppTarget.Route("pos-report"), "pos:reports"),
    if (standalone) null else app("inbox", "Inbox", Icons.Outlined.Forum, 0xFF0891B2, AppTarget.Route("messaging")),
    app("staff", "Staff", Icons.Outlined.ManageAccounts, 0xFF7C3AED, AppTarget.Route("staff"), "user:update", "user:create"),
    app("taxes", "Taxes", Icons.Outlined.Percent, 0xFFCA8A04, AppTarget.Route("taxes"), "tax:update", "tax:create"),
    if (isRetail) null else app("floor", "Floor Setup", Icons.Outlined.GridView, 0xFF0D9488, AppTarget.Route("tables-manager"), "tables:edit", "tables:create"),
    app("modifiers", "Modifiers", Icons.Outlined.LocalOffer, 0xFF9333EA, AppTarget.Route("modifier-groups"), "product:update"),
    app("settings", "Settings", Icons.Outlined.AccountBalance, 0xFF334155, AppTarget.Route("business"), "organization:update"),
    app("backup", "Backup", Icons.Outlined.Backup, 0xFF1E293B, AppTarget.Route("backup"), "organization:update"),
    if (standalone) null else app("sync", "Sync", Icons.Outlined.CloudSync, 0xFF0284C7, AppTarget.Route("sync")),
).filter { allowed(permissions, it.need) }

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class DashboardViewModel @Inject constructor(
    val config: DeviceConfig,
    private val auth: AuthRepository,
    private val account: AccountRepository,
    sales: SaleDao,
    sessions: CashSessionRepository,
    opQueue: OpQueueDao,
    settingsDao: SettingsDao,
) : ViewModel() {
    val user: AuthRepository.LoggedIn? get() = auth.appUser
    val email: String? get() = account.account.value?.email
    val organizationName: String? get() = account.account.value?.organizationName
    val signedInOffline: Boolean get() = account.account.value == null

    private val startOfDay: Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val todayTotal: StateFlow<Double> = sales.totalSinceFlow(startOfDay).stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)
    val todayCount: StateFlow<Int> = sales.countSinceFlow(startOfDay).stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val shiftOpen: StateFlow<Boolean?> = sessions.openSession().map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val toSync: StateFlow<Int> = opQueue.queuedCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val isRetail: StateFlow<Boolean> = settingsDao.byKeyFlow("pos.mode")
        .map { s -> s != null && runCatching { (Json.parseToJsonElement(s.valueJson) as? JsonPrimitive)?.content == "retail" }.getOrDefault(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun apps(isRetail: Boolean): List<DashboardApp> =
        dashboardApps(auth.appUser?.permissions.orEmpty(), config.standalone, isRetail)

    fun signOut() = account.signOut()
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Home after sign-in (mobile app shell, 2026-11-23), laid out like Odoo's mobile home: who is signed in, a strip of
 * today's figures from this device, then a grid of coloured app tiles. Point of Sale, Tables and Orders open the till
 * behind its PIN gate; the rest open their screens directly as the signed-in person.
 */
@Composable
fun DashboardScreen(
    onOpenApp: (AppTarget) -> Unit,
    onOpenSync: () -> Unit,
    onSignedOut: () -> Unit,
    vm: DashboardViewModel = hiltViewModel(),
) {
    val isRetail by vm.isRetail.collectAsStateWithLifecycle()
    val total by vm.todayTotal.collectAsStateWithLifecycle()
    val count by vm.todayCount.collectAsStateWithLifecycle()
    val shiftOpen by vm.shiftOpen.collectAsStateWithLifecycle()
    val toSync by vm.toSync.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmSignOut by remember { mutableStateOf(false) }
    val apps = remember(isRetail, vm.user) { vm.apps(isRetail) }
    val shown = if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!vm.config.standalone) SyncBanner(onOpenSync = onOpenSync)
        LicenseBanner(vm.config)
        SyncHealthBanner(vm.config)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(92.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DashboardHeader(vm, onOpenSync = onOpenSync, onSignOut = { confirmSignOut = true })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KpiCard("Today's sales", Money.format(total), "$count sale${if (count == 1) "" else "s"} on this device", MaterialTheme.colorScheme.primary)
                    KpiCard(
                        "Shift",
                        when (shiftOpen) { true -> "Open"; false -> "Closed"; null -> "…" },
                        if (shiftOpen == true) "Till is taking money" else "Open one in Point of Sale",
                        if (shiftOpen == true) Color(0xFF0E9F6E) else Color(0xFFF59E0B),
                    )
                    if (!vm.config.standalone) {
                        KpiCard(
                            "To sync",
                            toSync.toString(),
                            if (toSync == 0) "Everything is uploaded" else "Uploads when online",
                            if (toSync == 0) Color(0xFF0284C7) else Color(0xFFF59E0B),
                            onClick = onOpenSync,
                        )
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(shown, key = { it.id }) { a -> AppTile(a) { onOpenApp(a.target) } }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (apps.isEmpty()) "Your account has no apps on this device. Ask an administrator for access."
                        else "No app matches “$query”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    if (toSync > 0) "$toSync change${if (toSync == 1) "" else "s"} still waiting to upload stay on this device and sync as soon as it is online."
                    else "You will need to sign in again to use the app.",
                )
            },
            confirmButton = {
                Button(onClick = { confirmSignOut = false; vm.signOut(); onSignedOut() }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DashboardHeader(vm: DashboardViewModel, onOpenSync: () -> Unit, onSignOut: () -> Unit) {
    val name = vm.user?.displayName.orEmpty()
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                (vm.organizationName ?: vm.config.businessName).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                greeting() + (name.substringBefore(' ').takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (vm.signedInOffline && !vm.config.standalone) {
                Text("Signed in with PIN · online apps need an account sign-in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            val hue = avatarColor(name)
            Surface(onClick = { menuOpen = true }, shape = CircleShape, color = hue, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(initials(name), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(min = 200.dp)) {
                    Text(name.ifBlank { "Signed in" }, style = MaterialTheme.typography.titleSmall)
                    vm.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider()
                if (!vm.config.standalone) {
                    DropdownMenuItem(
                        text = { Text("Sync") },
                        leadingIcon = { Icon(Icons.Outlined.CloudSync, null) },
                        onClick = { menuOpen = false; onOpenSync() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Sign out") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                    onClick = { menuOpen = false; onSignOut() },
                )
            }
        }
    }
}

@Composable
private fun KpiCard(title: String, value: String, caption: String, accent: Color, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Column(Modifier.width(168.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) { content() }
    } else {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) { content() }
    }
}

/** An Odoo-style app icon: a rounded, softly shaded colour tile with a white glyph, the name underneath. */
@Composable
private fun AppTile(app: DashboardApp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            modifier = Modifier.size(64.dp).shadow(4.dp, RoundedCornerShape(20.dp)).pressScale(interaction),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(app.color.copy(alpha = 0.85f), app.color)),
                    RoundedCornerShape(20.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(app.icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(34.dp),
        )
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }

private fun avatarColor(name: String): Color = com.poscafe.pos.ui.components.avatarHue(name.ifBlank { "?" })
