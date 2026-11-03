package com.poscafe.pos.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Back-office side drawer, styled and grouped like the web app's sidebar
 * (`web/src/components/layout/app-shell.tsx`, "Luxury Sky" theme): a
 * midnight-sapphire panel, the brand tile, a Dashboard link, then
 * collapsible sections in the web's order and with its labels. Only screens
 * the till actually has are listed; sections with nothing on Android are left
 * out. The section holding the active item opens on its own.
 */
@Composable
internal fun HomeSidebar(
    businessName: String,
    cashierName: String,
    /** Signed-in staff's permissions; the drawer only lists what they may open. */
    permissions: Set<String>,
    standalone: Boolean,
    isRetail: Boolean,
    currentTab: HomeTab,
    onTab: (HomeTab) -> Unit,
    onNavigate: (String) -> Unit,
) {
    val sections = remember(standalone, isRetail, permissions) {
        sidebarSections(standalone, isRetail).mapNotNull { s ->
            s.copy(items = s.items.filter { allowed(permissions, it.need) }).takeIf { it.items.isNotEmpty() }
        }
    }
    val activeSection = sections.firstOrNull { s -> s.items.any { it.target == SidebarTarget.Tab(currentTab) } }?.title

    // Accordion state, like the web's expandedSections (kept across rotation).
    val expanded = rememberSaveable(
        saver = listSaver(save = { it.filterValues { open -> open }.keys.toList() }, restore = { keys ->
            mutableStateMapOf<String, Boolean>().apply { keys.forEach { put(it, true) } }
        }),
    ) { mutableStateMapOf<String, Boolean>().apply { activeSection?.let { put(it, true) } } }

    ModalDrawerSheet(
        drawerContainerColor = Color.Transparent,
        drawerShape = RectangleShape,
        modifier = Modifier.width(300.dp),
    ) {
        Column(Modifier.fillMaxSize().background(SidebarColors.panel)) {
            BrandHeader(businessName)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                sections.forEach { section ->
                    val title = section.title
                    if (title == null) {
                        section.items.forEach { item ->
                            SidebarLink(item, active = item.isActive(currentTab), indent = false) { item.open(onTab, onNavigate) }
                        }
                        return@forEach
                    }
                    val open = expanded[title] == true
                    SectionHeader(title, section.icon, open) { expanded[title] = !open }
                    AnimatedVisibility(
                        visible = open,
                        enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            section.items.forEach { item ->
                                SidebarLink(item, active = item.isActive(currentTab), indent = true) {
                                    item.open(onTab, onNavigate)
                                }
                            }
                        }
                    }
                }
            }
            SignedInFooter(cashierName, standalone)
        }
    }
}

// ---------------------------------------------------------------------------
// Model — mirrors NAV_SECTIONS in the web app-shell.
// ---------------------------------------------------------------------------

internal sealed interface SidebarTarget {
    /** One of the home shell's own tabs (switches tab, closes the drawer). */
    data class Tab(val tab: HomeTab) : SidebarTarget

    /** A separate screen in the app NavHost. */
    data class Route(val route: String) : SidebarTarget
}

private data class SidebarItem(
    val label: String,
    val icon: ImageVector,
    val target: SidebarTarget,
    /** Any one of these permissions opens it; null = everyone signed in. */
    val need: Set<String>? = null,
) {
    fun isActive(current: HomeTab) = target == SidebarTarget.Tab(current)

    fun open(onTab: (HomeTab) -> Unit, onNavigate: (String) -> Unit) = when (target) {
        is SidebarTarget.Tab -> onTab(target.tab)
        is SidebarTarget.Route -> onNavigate(target.route)
    }
}

private data class SidebarSection(val title: String?, val icon: ImageVector, val items: List<SidebarItem>)

private fun tab(label: String, icon: ImageVector, tab: HomeTab, vararg need: String) =
    SidebarItem(label, icon, SidebarTarget.Tab(tab), need.toSet().ifEmpty { null })

private fun route(label: String, icon: ImageVector, route: String, vararg need: String) =
    SidebarItem(label, icon, SidebarTarget.Route(route), need.toSet().ifEmpty { null })

/**
 * Staff synced from the server carry real permission strings. Staff created on a standalone
 * till carry a role word instead: "admin" opens everything, "cashier" the front-of-house set.
 */
private val CASHIER = setOf(
    "pos:checkout", "pos:read", "pos:hold", "pos:open_session", "pos:close_session",
    "cash_session:open", "cash_session:close", "cash_session:read", "partner:read", "partner:create", "tables:view",
)

internal fun allowed(permissions: Set<String>, need: Set<String>?): Boolean = when {
    need == null -> true
    "admin" in permissions -> true
    need.any { it in permissions } -> true
    "cashier" in permissions || permissions.isEmpty() -> need.any { it in CASHIER }
    else -> false
}

private const val MANAGE_CATALOG = "product:update"

/**
 * Ordered like a shift: sell, settle, cash up, report, then manage. Mirrors the web POS area,
 * limited to what the till has; Backup sits under Device for every manager.
 */
private fun sidebarSections(standalone: Boolean, isRetail: Boolean): List<SidebarSection> = listOfNotNull(
    SidebarSection(null, Icons.Outlined.Dashboard, listOf(route("Dashboard", Icons.Outlined.Dashboard, "reports", "pos:reports"))),
    SidebarSection(
        "Sell", Icons.Outlined.LocalCafe,
        listOfNotNull(
            tab("POS Terminal", Icons.Outlined.LocalCafe, HomeTab.Sell),
            // Retail tills have no dine-in tables (the Tables tab is hidden too).
            if (isRetail) null else tab("Floor Plan", Icons.Outlined.TableRestaurant, HomeTab.Tables),
        ),
    ),
    SidebarSection("Receipts", Icons.Outlined.Receipt, listOf(tab("POS Receipts", Icons.Outlined.Receipt, HomeTab.Orders))),
    SidebarSection(
        "Cash & Shifts", Icons.Outlined.PointOfSale,
        listOf(
            route("Shift History", Icons.AutoMirrored.Outlined.ReceiptLong, "shift-history", "cash_session:read", "pos:reports"),
            route("Cash Registers", Icons.Outlined.PointOfSale, "registers", "cash_register:update", "cash_register:create"),
            route("Expenses", Icons.Outlined.Description, "expenses", "expense:create", "expense:read"),
        ),
    ),
    SidebarSection("Reports", Icons.Outlined.Insights, listOf(route("POS Report", Icons.Outlined.Insights, "pos-report", "pos:reports"))),
    SidebarSection(
        "Catalog", Icons.Outlined.LocalOffer,
        listOf(
            route("Menu", Icons.Outlined.LocalCafe, "menu-manager", MANAGE_CATALOG, "product:create"),
            route("Modifiers", Icons.Outlined.LocalOffer, "modifier-groups", MANAGE_CATALOG),
            route("Accompaniments", Icons.Outlined.LocalOffer, "accompaniment-groups", MANAGE_CATALOG),
            route("Products", Icons.Outlined.Inventory2, "products", "product:read", MANAGE_CATALOG),
        ),
    ),
    SidebarSection(
        "Stock", Icons.Outlined.Inventory2,
        listOf(
            route("Stock Levels", Icons.Outlined.Inventory2, "stock", "inventory:read"),
            route("Stock Count", Icons.Outlined.Checklist, "inventory-count", "inventory_count:count"),
            route("Receive Stock", Icons.Outlined.ShoppingCart, "purchases", "purchase_order:receive", "inventory_doc:create"),
            route("Suppliers", Icons.Outlined.Business, "suppliers", "supplier:read", "supplier:manage"),
        ),
    ),
    SidebarSection("Customers", Icons.Outlined.People, listOf(route("Customers", Icons.Outlined.People, "customers", "partner:read"))),
    SidebarSection(
        "Setup", Icons.Outlined.Settings,
        listOfNotNull(
            route("Company Settings", Icons.Outlined.AccountBalance, "business", "organization:update"),
            route("Staff & PINs", Icons.Outlined.ManageAccounts, "staff", "user:update", "user:create"),
            route("Tax Rates", Icons.Outlined.Percent, "taxes", "tax:update", "tax:create"),
            if (isRetail) null else route("Tables", Icons.Outlined.GridView, "tables-manager", "tables:edit", "tables:create"),
        ),
    ),
    SidebarSection(
        "Device", Icons.Outlined.CloudSync,
        listOfNotNull(
            route("Backup & Restore", Icons.Outlined.Backup, "backup", "organization:update"),
            if (standalone) null else route("Sync", Icons.Outlined.CloudSync, "sync"),
            if (standalone) null else route("Inbox", Icons.Outlined.Forum, "messaging"),
        ),
    ),
)

// ---------------------------------------------------------------------------
// Look — the web sidebar's "Luxury Sky" palette (web/src/lib/sidebar-theme.tsx).
// ---------------------------------------------------------------------------

private object SidebarColors {
    val panel = Brush.verticalGradient(listOf(Color(0xFF0B2545), Color(0xFF0C3A66), Color(0xFF0D5689)))
    val border = Color(0x1FBAE6FD)
    val text = Color(0xDBEAF4FD)
    val muted = Color(0x9EADD6F5)
    val active = Color.White
    val activeBg = Brush.horizontalGradient(listOf(Color(0x3D7DD3FC), Color(0x0D7DD3FC)))
    val activeBar = Color(0xFFD9B872) // champagne
    val brand = Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF0369A1)))
}

@Composable
private fun BrandHeader(businessName: String) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .shadow(6.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(SidebarColors.brand)
                .border(1.dp, SidebarColors.activeBar.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.LocalCafe, null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                businessName,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = (-0.3).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "POINT OF SALE",
                color = SidebarColors.muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(SidebarColors.border))
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector, open: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 0f else -90f, tween(200), label = "chevron")
    SidebarRow(onClick = onToggle) {
        Icon(icon, null, tint = SidebarColors.text, modifier = Modifier.size(18.dp))
        Text(
            title,
            color = SidebarColors.text,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.KeyboardArrowDown, null,
            tint = SidebarColors.text.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp).rotate(rotation),
        )
    }
}

@Composable
private fun SidebarLink(item: SidebarItem, active: Boolean, indent: Boolean, onClick: () -> Unit) {
    Box {
        SidebarRow(onClick = onClick, active = active, indent = indent) {
            val tint = if (active) SidebarColors.active else SidebarColors.text
            Icon(item.icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Text(
                item.label,
                color = tint,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(24.dp)
                    .shadow(6.dp, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp), ambientColor = SidebarColors.activeBar, spotColor = SidebarColors.activeBar)
                    .background(SidebarColors.activeBar, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)),
            )
        }
    }
}

@Composable
private fun SidebarRow(
    onClick: () -> Unit,
    active: Boolean = false,
    indent: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .then(if (active) Modifier.background(SidebarColors.activeBg) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = if (indent) 24.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun SignedInFooter(cashierName: String, standalone: Boolean) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SidebarColors.border))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF34D399)))
        Spacer(Modifier.width(10.dp))
        Text(
            if (standalone) "$cashierName · Standalone" else cashierName,
            color = SidebarColors.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
