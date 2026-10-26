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

// =====================================================================
// Checkout
// =====================================================================

internal fun quickCashAmounts(total: Double): List<Double> {
    val denoms = listOf(1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0)
    return denoms.map { ceil(total / it) * it }.filter { it >= total }.distinct().sorted().take(4)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CheckoutSheet(
    total: Double,
    charging: Boolean,
    error: String?,
    hasCustomer: Boolean,
    /** Configured tiles; when empty the legacy fixed tiles are shown. */
    methods: List<com.poscafe.pos.data.local.entity.PaymentMethodEntity>,
    onDismiss: () -> Unit,
    onCharge: (tenders: List<SaleRepository.Tender>) -> Unit,
) {
    // Split tender: legs accumulate until they cover the total. The common
    // single-payment case is still one tap — the amount field is prefilled to
    // the remaining balance, so "Complete" commits it and fires immediately.
    val legs = remember { mutableStateListOf<SaleRepository.Tender>() }
    val paid = legs.sumOf { it.amount }
    val remaining = (total - paid).coerceAtLeast(0.0)

    var method by remember { mutableStateOf("cash") }
    // The configured tile picked (its receiving account travels with the tender).
    val tiles = remember(methods, hasCustomer) { methods.filter { it.kind != "store_credit" || hasCustomer } }
    var tileId by remember(tiles) { mutableStateOf(tiles.firstOrNull { it.kind == "cash" }?.id ?: tiles.firstOrNull()?.id) }
    val tile = tiles.firstOrNull { it.id == tileId }
    LaunchedEffect(tile) { tile?.let { method = it.kind } }
    var tendered by remember(remaining) { mutableStateOf("%.0f".format(remaining)) }
    var reference by remember { mutableStateOf("") }
    val pending = tendered.toDoubleOrNull() ?: 0.0
    val covered = remaining <= 0.01
    val wouldCover = paid + pending >= total - 0.01
    // Change is only meaningful on a cash overpay.
    val change = (paid + pending - total).let { if (it > 0 && method == "cash") it else 0.0 }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Checkout", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Amount due", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.format(total), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    if (legs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        KVRow("Paid", Money.format(paid))
                        KVRow(
                            "Remaining",
                            Money.format(remaining),
                            emphasize = true,
                            valueColor = if (covered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Committed tender legs (removable).
            legs.forEachIndexed { i, leg ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        methods.firstOrNull { it.kind == leg.method && it.accountId == leg.accountId }?.label ?: tenderLabel(leg.method),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(Money.format(leg.amount), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { legs.removeAt(i) }) {
                        Icon(Icons.Outlined.DeleteOutline, "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (!covered) {
                Spacer(Modifier.height(16.dp))
                Text(if (legs.isEmpty()) "Payment method" else "Add payment", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                if (tiles.isNotEmpty()) {
                    tiles.chunked(3).forEachIndexed { rowIndex, row ->
                        if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { m ->
                                PayMethodCard(m.label, kindIcon(m.kind), tileId == m.id, Modifier.weight(1f)) { tileId = m.id }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayMethodCard("Cash", Icons.Outlined.Payments, method == "cash", Modifier.weight(1f)) { method = "cash" }
                    PayMethodCard("Card", Icons.Outlined.CreditCard, method == "card", Modifier.weight(1f)) { method = "card" }
                    PayMethodCard("Mobile", Icons.Outlined.Smartphone, method == "mobile_money", Modifier.weight(1f)) { method = "mobile_money" }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayMethodCard("Bank", Icons.Outlined.AccountBalance, method == "bank", Modifier.weight(1f)) { method = "bank" }
                    if (hasCustomer) {
                        PayMethodCard("Store credit", Icons.Outlined.Loyalty, method == "store_credit", Modifier.weight(1f)) { method = "store_credit" }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.weight(1f))
                }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = tendered,
                    onValueChange = { tendered = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(if (method == "cash") "Amount tendered" else "Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (method == "cash") {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        quickCashAmounts(remaining).forEach { amt ->
                            Surface(
                                onClick = { tendered = "%.0f".format(amt) },
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Text(
                                    Money.bare(amt),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
                if (total > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Split evenly", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf(2, 3, 4).forEach { n ->
                            Surface(
                                onClick = { tendered = "%.0f".format(total / n) },
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Text("÷$n", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
                if (method == "card" || method == "mobile_money" || method == "bank") {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = reference,
                        onValueChange = { reference = it },
                        label = { Text(if (tile?.requiresReference == true) "Reference (required)" else "Reference (optional)") },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (change > 0) {
                    Spacer(Modifier.height(12.dp))
                    KVRow("Change", Money.format(change), emphasize = true, valueColor = MaterialTheme.colorScheme.primary)
                }
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = when {
                    charging -> "Completing…"
                    covered || wouldCover -> "Complete payment"
                    else -> "Add payment"
                },
                onClick = {
                    if (covered && pending <= 0.0) {
                        onCharge(legs.toList())
                    } else if (pending > 0.0) {
                        legs.add(
                            SaleRepository.Tender(
                                method,
                                pending,
                                reference.takeIf { it.isNotBlank() },
                                accountId = tile?.accountId.takeIf { tile?.kind == method },
                            ),
                        )
                        reference = ""
                        if (legs.sumOf { it.amount } >= total - 0.01) onCharge(legs.toList())
                    }
                },
                enabled = !charging && (covered || pending > 0.0) &&
                    (covered || tile?.requiresReference != true || reference.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun kindIcon(kind: String): ImageVector = when (kind) {
    "cash" -> Icons.Outlined.Payments
    "card" -> Icons.Outlined.CreditCard
    "mobile_money" -> Icons.Outlined.Smartphone
    "bank" -> Icons.Outlined.AccountBalance
    "store_credit" -> Icons.Outlined.Loyalty
    else -> Icons.Outlined.Payments
}

internal fun tenderLabel(method: String): String = when (method) {
    "cash" -> "Cash"
    "card" -> "Card"
    "mobile_money" -> "Mobile money"
    "bank" -> "Bank"
    "store_credit" -> "Store credit"
    else -> method
}

/** Pick the lines that go on THIS bill; the rest stay on the tab. Each split
 *  bill settles as its own sale. */
@Composable
internal fun SplitBillDialog(
    lines: List<CartEngine.CartLine>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }
    val selectedTotal = CartEngine.totals(lines.filter { it.lineId in selected }).total
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Split bill", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Select the items for this bill. The rest stay on the tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                lines.forEach { line ->
                    val checked = line.lineId in selected
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on -> if (on) selected.add(line.lineId) else selected.remove(line.lineId) },
                        )
                        Text(
                            "${if (line.quantity % 1.0 == 0.0) line.quantity.toInt() else line.quantity} × ${line.name}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(Money.bare(CartEngine.lineTotals(line).net), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected.toSet()) },
                enabled = selected.isNotEmpty() && selected.size < lines.size,
                shape = MaterialTheme.shapes.medium,
            ) { Text("Charge ${Money.format(selectedTotal)}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PayMethodCard(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier.height(72.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon, null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// =====================================================================
// Success overlay
// =====================================================================

@Composable
internal fun SuccessOverlay(sale: SaleRepository.CompletedSale, onNewOrder: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(sale.localId) { shown = true }
    val checkScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.4f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "checkScale",
    )
    AnimatedVisibility(visible = shown, enter = fadeIn(), exit = fadeOut()) {
        Surface(color = MaterialTheme.colorScheme.scrim, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(88.dp)
                                .scale(checkScale)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Check, null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Payment successful", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Receipt ${sale.provisionalNumber}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Total paid", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            Money.format(sale.totals.total),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(24.dp))
                        PrimaryButton(
                            text = "New order",
                            onClick = onNewOrder,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
