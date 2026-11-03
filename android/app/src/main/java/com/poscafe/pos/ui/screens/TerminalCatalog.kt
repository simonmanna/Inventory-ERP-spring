package com.poscafe.pos.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.dao.CustomerDao
import com.poscafe.pos.data.local.dao.HoldDao
import com.poscafe.pos.data.local.dao.MenuDao
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.dao.ProductCategoryDao
import com.poscafe.pos.data.local.dao.ProductDao
import com.poscafe.pos.data.local.dao.ProductPackagingDao
import com.poscafe.pos.data.local.dao.RegisterDao
import com.poscafe.pos.data.local.dao.SettingsDao
import com.poscafe.pos.data.local.dao.TableDao
import com.poscafe.pos.data.local.entity.*
import com.poscafe.pos.data.repo.*
import com.poscafe.pos.printing.ReceiptPrinter
import com.poscafe.pos.ui.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.ceil

// Terminal catalog grid, category chips and the cart panel.

@Composable
internal fun CategoryChips(
    categories: List<MenuCategoryEntity>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryChip("All", selected == null) { onSelect(null) }
        categories.forEach { cat ->
            CategoryChip(cat.name, selected == cat.id) { onSelect(cat.id) }
        }
    }
}

@Composable
internal fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun CafeCatalog(
    categories: List<MenuCategoryEntity>,
    selectedCat: String?,
    menuItems: List<MenuItemEntity>,
    vm: TerminalViewModel,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    serverUrl: String?,
    wide: Boolean,
) {
    CategoryChips(categories = categories, selected = selectedCat, onSelect = { vm.selectedCategory.value = it })
    Spacer(Modifier.height(12.dp))
    if (menuItems.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.RestaurantMenu,
            title = "No items here yet",
            subtitle = "Pull from the server on the More tab to load the menu.",
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // Per-item quantity already in the cart → drives the "×N" badge so the
        // cashier sees what's been rung up without opening the cart.
        val cartQty = vm.cart.groupBy { it.menuItemId }
            .mapNotNull { (id, lines) -> id?.let { it to lines.sumOf { l -> l.quantity }.toInt() } }
            .toMap()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = if (wide) 16.dp else 96.dp),
        ) {
            items(menuItems, key = { it.id }) { item ->
                CatalogCard(
                    imageUrl = resolveAssetUrl(serverUrl, item.image),
                    name = item.name,
                    priceLabel = Money.format(item.basePriceMajor ?: 0.0),
                    inCartQty = cartQty[item.id] ?: 0,
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.onItemTap(item)
                    },
                )
            }
        }
    }
}

@Composable
internal fun RetailCatalog(
    categories: List<ProductCategoryEntity>,
    selectedCategory: String?,
    products: List<ProductEntity>,
    vm: TerminalViewModel,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    serverUrl: String?,
    wide: Boolean,
) {
    // Product category chips
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryChip("All", selectedCategory == null) { vm.selectedProductCategory.value = null }
        categories.forEach { cat ->
            CategoryChip(cat.name, selectedCategory == cat.id) { vm.selectedProductCategory.value = cat.id }
        }
    }
    Spacer(Modifier.height(12.dp))
    if (products.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.RestaurantMenu,
            title = "No products",
            subtitle = "Sync products from the server, or scan a barcode above.",
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        val cartQty = vm.cart.groupBy { it.productId }
            .mapNotNull { (id, lines) -> id?.let { it to lines.sumOf { l -> l.quantity }.toInt() } }
            .toMap()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = if (wide) 16.dp else 96.dp),
        ) {
            items(products, key = { it.id }) { product ->
                CatalogCard(
                    imageUrl = resolveAssetUrl(serverUrl, product.image),
                    name = product.name,
                    priceLabel = Money.format(product.salesPrice),
                    inCartQty = cartQty[product.id] ?: 0,
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.onProductTap(product)
                    },
                )
            }
        }
    }
}

/**
 * Shared catalog tile for both menu items and retail products. A tapped card
 * adds one unit; the green highlight + "×N" badge on the photo make the card
 * self-report how many are already on the bill.
 */
@Composable
internal fun CatalogCard(
    imageUrl: String?,
    name: String,
    priceLabel: String,
    inCartQty: Int,
    onTap: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val inCart = inCartQty > 0
    Surface(
        onClick = onTap,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (inCart) 3.dp else 1.dp,
        modifier = Modifier
            .pressScale(interaction)
            .border(
                width = if (inCart) 1.5.dp else 1.dp,
                color = if (inCart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large,
            ),
    ) {
        Column(Modifier.padding(8.dp)) {
            // No photo: a short initial banner, so a phone shows a few rows of items per
            // screen instead of two cards of empty placeholder.
            Box(Modifier.fillMaxWidth().aspectRatio(if (imageUrl == null) 2.6f else 1.2f)) {
                ItemImage(imageUrl, name, Modifier.fillMaxSize())
                if (inCart) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(999.dp),
                        shadowElevation = 2.dp,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    ) {
                        Text(
                            "×$inCartQty",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Reserve two lines so one- and two-line cards align in the grid.
                modifier = Modifier.heightIn(min = 40.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    priceLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CartBar(count: Int, total: Double, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().height(64.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$count", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    "View cart",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                Money.format(total),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
internal fun CartPanel(vm: TerminalViewModel, table: PosTableEntity?, onCharge: () -> Unit, showHold: Boolean = false, onHold: () -> Unit = {}, onSplit: () -> Unit = {}) {
    val totals = vm.totals
    var orderDiscountDialog by remember { mutableStateOf(false) }
    var lineDiscountFor by remember { mutableStateOf<CartEngine.CartLine?>(null) }
    if (orderDiscountDialog) {
        DiscountDialog(
            title = "Order discount",
            current = vm.orderDiscountPercent,
            onApply = { vm.setOrderDiscount(it); orderDiscountDialog = false },
            onDismiss = { orderDiscountDialog = false },
        )
    }
    lineDiscountFor?.let { l ->
        DiscountDialog(
            title = "Discount — ${l.name}",
            current = l.discountPercent,
            onApply = { vm.setLineDiscount(l.lineId, it); lineDiscountFor = null },
            onDismiss = { lineDiscountFor = null },
        )
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Current order", style = MaterialTheme.typography.titleMedium)
            if (vm.cart.isNotEmpty()) {
                TextButton(onClick = { vm.clear() }) { Text("Clear") }
            }
        }
        table?.let {
            StatusPill(
                // Skip the name when it just repeats the number ("Table 3 · Table 3").
                "Table ${it.number}" + (it.name?.takeIf { n -> n.isNotBlank() && n != "Table ${it.number}" }
                    ?.let { n -> " · $n" } ?: ""),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                container = MaterialTheme.colorScheme.primaryContainer,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (vm.cart.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.ReceiptLong,
                title = "Cart is empty",
                subtitle = "Tap items on the menu to add them.",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(vm.cart, key = { it.lineId }) { line ->
                    CartLineRow(
                        line = line,
                        lineTotal = totals.lines[line.lineId]?.net ?: 0.0,
                        onDec = { vm.changeQty(line.lineId, -1.0) },
                        onInc = { vm.changeQty(line.lineId, +1.0) },
                        onRemove = { vm.removeLine(line.lineId) },
                        onDiscount = { lineDiscountFor = line },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KVRow("Subtotal", Money.format(totals.subtotal))
            if (totals.discountTotal > 0) KVRow("Discount", "− ${Money.format(totals.discountTotal)}")
            if (totals.taxTotal > 0) KVRow("Tax", Money.format(totals.taxTotal))
            KVRow("Total", Money.format(totals.total), emphasize = true, valueColor = MaterialTheme.colorScheme.primary)
            if (vm.cart.isNotEmpty()) {
                TextButton(
                    onClick = { orderDiscountDialog = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (vm.orderDiscountPercent > 0) "Order discount ${fmtPct(vm.orderDiscountPercent)}% — edit" else "+ Add order discount",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        vm.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        // Dine-in tab controls: fire a KOT round, bank the tab, or split the bill.
        if (table != null && vm.cart.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.fireKitchen() },
                    enabled = vm.hasUnfiredLines,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Outlined.RestaurantMenu, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fire")
                }
                OutlinedButton(
                    onClick = { vm.saveTab() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Outlined.TableRestaurant, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save tab")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (vm.cart.size > 1) {
                OutlinedButton(
                    onClick = onSplit,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Split bill") }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (showHold && vm.cart.isNotEmpty()) {
            OutlinedButton(
                onClick = onHold,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Outlined.ReceiptLong, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Hold order")
            }
            Spacer(Modifier.height(8.dp))
        }
        PrimaryButton(
            text = if (vm.cart.isEmpty()) "Charge" else "Charge ${Money.format(totals.total)}",
            onClick = onCharge,
            enabled = vm.cart.isNotEmpty() && !vm.charging,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun CartLineRow(
    line: CartEngine.CartLine,
    lineTotal: Double,
    onDec: () -> Unit,
    onInc: () -> Unit,
    onRemove: () -> Unit,
    onDiscount: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(line.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = buildList {
                    line.variantName?.let { add(it) }
                    line.accompaniments.forEach { add(it.name) }
                    line.modifiers.forEach { add(it.name) }
                    if (line.discountPercent > 0) add("−${fmtPct(line.discountPercent)}%")
                    line.note?.let { add("“$it”") }
                }
                if (detail.isNotEmpty()) {
                    Text(
                        detail.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(Money.format(lineTotal), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline, "Remove",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                TextButton(onClick = onDiscount, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (line.discountPercent > 0) "${fmtPct(line.discountPercent)}% off" else "Disc", style = MaterialTheme.typography.labelMedium)
                }
            }
            QuantityStepper(
                quantity = line.quantity.toInt(),
                onDecrement = onDec,
                onIncrement = onInc,
                buttonSize = 34.dp,
            )
        }
    }
}
