package com.poscafe.pos.ui.screens

import androidx.activity.compose.BackHandler
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

// =====================================================================
// Screen
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: TerminalViewModel, onMenu: (() -> Unit)? = null) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val menuItems by vm.items.collectAsStateWithLifecycle()
    val selectedCat by vm.selectedCategory.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val sessionLoad by vm.sessionLoad.collectAsStateWithLifecycle()
    val queuedOps by vm.queuedOps.collectAsStateWithLifecycle()
    val selectedTable by vm.selectedTable.collectAsStateWithLifecycle()
    val tables by vm.tables.collectAsStateWithLifecycle()
    val isRetail by vm.isRetailMode.collectAsStateWithLifecycle()
    val productCategories by vm.productCategories.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val selectedProdCat by vm.selectedProductCategory.collectAsStateWithLifecycle()
    val searchQuery by vm.productSearchQuery.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val selectedCustomer = vm.selectedCustomer
    val needsTable by vm.needsTable.collectAsStateWithLifecycle()
    val takeaway by vm.takeaway.collectAsStateWithLifecycle()
    val openTabIds by vm.openTabTableIds.collectAsStateWithLifecycle()
    val foreignShiftOwner by vm.foreignShiftOwner.collectAsStateWithLifecycle()
    val signedInId = vm.cashier?.userId
    LaunchedEffect(signedInId) { vm.currentUserId.value = signedInId }
    // Cafe mode: Back from an order returns to the floor (a table's order stays on its tab).
    BackHandler(enabled = !isRetail && !needsTable && (selectedTable != null || vm.cart.isEmpty())) {
        vm.backToFloor()
    }

    var showCheckout by remember { mutableStateOf(false) }
    var showCartSheet by remember { mutableStateOf(false) }
    var showTablePicker by remember { mutableStateOf(false) }
    var showOpenSession by remember { mutableStateOf(false) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var showCustomerPicker by remember { mutableStateOf(false) }
    val holdMode = remember { mutableStateOf<HoldDialogMode>(HoldDialogMode.Save) }
    var showHoldDialog by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(false) }
    var splitSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var splitCheckout by remember { mutableStateOf(false) }
    var showReservations by remember { mutableStateOf(false) }
    val holds by vm.holds.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            TerminalHeader(
                cashierName = vm.cashier?.displayName ?: "",
                // Don't flash "Cash session closed" on launch before the open session has loaded.
                sessionOpen = sessionLoad?.let { it.open != null } ?: true,
                queuedOps = if (vm.config.standalone) 0 else queuedOps,
                table = if (isRetail) null else selectedTable,
                customer = selectedCustomer,
                onMenu = onMenu,
                onPickTable = if (isRetail) null else ({ showTablePicker = true } as (() -> Unit)?),
                onPickCustomer = { showCustomerPicker = true },
                onRecallHold = { holdMode.value = HoldDialogMode.Recall; showHoldDialog = true },
                onOpenSession = { vm.loadRegisters(); showOpenSession = true },
                isRetailMode = isRetail,
                takeaway = takeaway,
                foreignShiftOwner = foreignShiftOwner,
                searchQuery = searchQuery,
                onSearchQueryChanged = vm::onSearchQueryChanged,
                onSearch = vm::searchProducts,
                onScanBarcode = { showBarcodeScanner = true },
            )
            if (needsTable) {
                // Cafe/restaurant: dine-in starts at the floor. No menu, no cart
                // until a table is picked (or Takeaway is chosen explicitly).
                TableFloor(
                    tables = tables,
                    selectedId = null,
                    openTabTableIds = openTabIds.toSet(),
                    onSelect = { vm.selectTable(it) },
                    onTakeaway = { vm.selectTable(null) },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                )
            } else Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    if (isRetail) {
                        RetailCatalog(
                            categories = productCategories,
                            selectedCategory = selectedProdCat,
                            products = products,
                            vm = vm,
                            haptics = haptics,
                            serverUrl = vm.config.serverUrl,
                            wide = wide,
                        )
                    } else {
                        CafeCatalog(
                            categories = categories,
                            selectedCat = selectedCat,
                            menuItems = menuItems,
                            vm = vm,
                            haptics = haptics,
                            serverUrl = vm.config.serverUrl,
                            wide = wide,
                        )
                    }
                }
                if (wide) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(topStart = 24.dp),
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                    ) {
                        CartPanel(
                            vm = vm,
                            table = if (isRetail) null else selectedTable,
                            onCharge = { showCheckout = true },
                            showHold = isRetail,
                            onHold = { holdMode.value = HoldDialogMode.Save; showHoldDialog = true },
                            onSplit = { showSplit = true },
                        )
                    }
                }
            }
        }

        if (!wide && !needsTable && vm.cart.isNotEmpty()) {
            CartBar(
                count = vm.cart.sumOf { it.quantity }.toInt(),
                total = vm.totals.total,
                onOpen = { showCartSheet = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }

        vm.successSale?.let { sale ->
            SuccessOverlay(
                sale = sale,
                onNewOrder = { vm.dismissSuccess() },
            )
        }
    }

    if (!isRetail) {
        vm.itemConfig?.let { config ->
            ItemConfigSheet(
                config = config,
                imageUrl = resolveAssetUrl(vm.config.serverUrl, config.item.image),
                onDismiss = { vm.dismissConfig() },
                onAdd = { variant, mods, accs, note, qty ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.addConfigured(config.item, variant, mods, accs, note, qty)
                },
            )
        }
    }

    if (showCartSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCartSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Box(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
                CartPanel(vm = vm, table = vm.selectedTable.collectAsStateWithLifecycle().value, onCharge = {
                    showCartSheet = false
                    showCheckout = true
                }, showHold = isRetail, onHold = { holdMode.value = HoldDialogMode.Save; showHoldDialog = true },
                    onSplit = { showCartSheet = false; showSplit = true })
            }
        }
    }

    val paymentMethods by vm.paymentMethods.collectAsStateWithLifecycle()

    if (showCheckout) {
        CheckoutSheet(
            total = vm.totals.total,
            charging = vm.charging,
            error = vm.error,
            hasCustomer = selectedCustomer != null,
            methods = paymentMethods,
            onDismiss = { showCheckout = false },
            onCharge = { tenders ->
                vm.charge(tenders) { showCheckout = false }
            },
        )
    }

    if (showSplit) {
        SplitBillDialog(
            lines = vm.cart,
            onConfirm = { ids -> splitSelection = ids; showSplit = false; splitCheckout = true },
            onDismiss = { showSplit = false },
        )
    }

    if (splitCheckout) {
        CheckoutSheet(
            total = CartEngine.totals(vm.cart.filter { it.lineId in splitSelection }).total,
            charging = vm.charging,
            error = vm.error,
            hasCustomer = selectedCustomer != null,
            methods = paymentMethods,
            onDismiss = { splitCheckout = false },
            onCharge = { tenders ->
                vm.settleLines(splitSelection, tenders) { splitCheckout = false }
            },
        )
    }

    if (!isRetail && showTablePicker) {
        TablePickerDialog(
            tables = tables,
            openTabTableIds = openTabIds.toSet(),
            selectedId = selectedTable?.id,
            onSelect = { vm.selectTable(it); showTablePicker = false },
            onDismiss = { showTablePicker = false },
            onReservations = { showTablePicker = false; showReservations = true },
            // A takeaway cart with items has no tab to bank on; pick a table for it instead.
            onFloor = if (selectedTable == null && vm.cart.isNotEmpty()) null else ({
                vm.backToFloor(); showTablePicker = false
            }),
        )
    }

    if (showReservations) {
        ReservationsDialog(onDismiss = { showReservations = false })
    }

    if (showOpenSession) {
        OpenSessionDialog(
            options = vm.opening,
            error = vm.openError,
            onOpen = { registerId, float, sourceId, notes ->
                vm.openSession(registerId, float, sourceId, notes) { showOpenSession = false }
            },
            onDismiss = { showOpenSession = false },
        )
    }

    if (showBarcodeScanner) {
        BarcodeScannerDialog(
            onDismiss = { showBarcodeScanner = false },
            onBarcodeDetected = { barcode -> vm.searchProducts(barcode) },
        )
    }

    if (showCustomerPicker) {
        CustomerPickerDialog(
            customers = customers,
            selectedId = selectedCustomer?.id,
            onSelect = { vm.selectCustomer(it); showCustomerPicker = false },
            onCreateCustomer = { name, phone -> vm.createCustomer(name, phone); showCustomerPicker = false },
            onDismiss = { showCustomerPicker = false },
        )
    }

    if (showHoldDialog) {
        HoldDialog(
            mode = holdMode.value,
            holds = holds,
            onSave = { name -> vm.saveHold(name); showHoldDialog = false },
            onRetrieve = { hold -> vm.retrieveHold(hold); showHoldDialog = false },
            onDelete = { hold -> vm.deleteHold(hold) },
            onDismiss = { showHoldDialog = false },
        )
    }
}

// =====================================================================
// Pieces
// =====================================================================

@Composable
internal fun TerminalHeader(
    cashierName: String,
    sessionOpen: Boolean,
    queuedOps: Int,
    table: PosTableEntity?,
    customer: CustomerEntity? = null,
    onMenu: (() -> Unit)?,
    onPickTable: (() -> Unit)?,
    onPickCustomer: () -> Unit = {},
    onRecallHold: () -> Unit = {},
    onOpenSession: () -> Unit,
    isRetailMode: Boolean = false,
    takeaway: Boolean = false,
    foreignShiftOwner: String? = null,
    searchQuery: String = "",
    onSearchQueryChanged: (String) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onScanBarcode: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: menu + title/cashier. weight(1f) lets it yield space to the
            // action chips so their labels never get crushed into a vertical wrap.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                onMenu?.let {
                    IconButton(onClick = it) { Icon(Icons.Filled.Menu, "Menu") }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            isRetailMode -> "Retail POS"
                            table != null -> table.name?.takeIf { it.isNotBlank() } ?: "Table ${table.number}"
                            takeaway -> "Takeaway"
                            else -> "New order"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            cashierName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (queuedOps > 0) {
                            StatusPill(
                                "$queuedOps to sync",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                        }
                    }
                }
            }
            // Right: compact chips. softWrap = false guarantees a chip label
            // truncates with an ellipsis instead of wrapping one letter per line.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderChip(
                    icon = Icons.Outlined.Person,
                    label = customer?.name,
                    fallback = "Customer",
                    active = customer != null,
                    onClick = onPickCustomer,
                )
                if (isRetailMode) {
                    HeaderChip(
                        icon = Icons.Outlined.HourglassBottom,
                        label = "Recall",
                        fallback = "Recall",
                        active = false,
                        onClick = onRecallHold,
                    )
                } else {
                    onPickTable?.let { pickTable ->
                        HeaderChip(
                            icon = Icons.Outlined.TableRestaurant,
                            label = table?.let { "Table ${it.number}" } ?: if (takeaway) "Takeaway" else "Table",
                            fallback = if (takeaway) "Takeaway" else "Table",
                            active = table != null,
                            onClick = pickTable,
                        )
                    }
                }
            }
        }
        // Cash-session warning gets its own full-width banner rather than fighting
        // the chips for space in the top row.
        if (!sessionOpen) {
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = onOpenSession,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Payments, null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Cash session closed",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Open now",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        if (sessionOpen && foreignShiftOwner != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Shift opened by $foreignShiftOwner. Close it (More → Close session) and open your own to sell.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
        if (isRetailMode) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChanged,
                    placeholder = { Text("Search by name, SKU, or barcode…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChanged("") }) {
                                Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(searchQuery) }),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                )
                IconButton(onClick = onScanBarcode) {
                    Icon(
                        Icons.Outlined.QrCodeScanner, "Scan barcode",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/** Compact header action chip: an icon plus a label that truncates (never
 *  wraps) so a long customer/table name can't stretch the row vertically. */
@Composable
internal fun HeaderChip(
    icon: ImageVector,
    label: String?,
    fallback: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    // Phones: an unset chip collapses to its icon so the "New order" title
    // isn't squeezed to "New …". Once set (customer/table picked) it shows the label.
    val iconOnly = !active && LocalConfiguration.current.screenWidthDp < 600
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = if (iconOnly) 10.dp else 12.dp, vertical = if (iconOnly) 10.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                icon, if (iconOnly) fallback else null,
                modifier = Modifier.size(if (iconOnly) 20.dp else 16.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!iconOnly) Text(
                label ?: fallback,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp),
            )
        }
    }
}

// =====================================================================
// Dialogs
// =====================================================================

@Composable
fun TablePickerDialog(
    tables: List<PosTableEntity>,
    selectedId: String?,
    onSelect: (PosTableEntity?) -> Unit,
    onDismiss: () -> Unit,
    openTabTableIds: Set<String> = emptySet(),
    onReservations: (() -> Unit)? = null,
    onFloor: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Select table", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Surface(
                    onClick = { onSelect(null) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selectedId == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Takeaway — no table",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items(tables, key = { it.id }) { t ->
                        val statusColor = tableStatusColor(t.status)
                        Surface(
                            onClick = { onSelect(t) },
                            shape = MaterialTheme.shapes.medium,
                            color = if (selectedId == t.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                width = if (selectedId == t.id) 1.5.dp else 1.dp,
                                color = if (selectedId == t.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    StatusDot(statusColor)
                                    Text("T${t.number}", style = MaterialTheme.typography.titleSmall)
                                }
                                t.name?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (t.id in openTabTableIds) {
                                    Text(
                                        "● open tab",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                onFloor?.let { TextButton(onClick = it) { Text("Floor") } }
                onReservations?.let { TextButton(onClick = it) { Text("Reservations") } }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun tableStatusColor(status: String): Color = when (status.lowercase()) {
    "available", "free" -> MaterialTheme.colorScheme.primary
    "occupied" -> MaterialTheme.colorScheme.error
    "reserved" -> com.poscafe.pos.ui.theme.LocalPosAccents.current.warning
    "needs_bill", "billed" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.outline
}
