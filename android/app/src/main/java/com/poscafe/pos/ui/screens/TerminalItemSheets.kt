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

// Terminal line editing: discounts and the item configuration sheet.

internal fun fmtPct(p: Double): String = if (p % 1.0 == 0.0) p.toInt().toString() else "%.1f".format(p)

/** Percentage discount entry — used for both a single line and the whole order. */
@Composable
internal fun DiscountDialog(
    title: String,
    current: Double,
    onApply: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var pct by remember { mutableStateOf(if (current > 0) fmtPct(current) else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pct,
                    onValueChange = { pct = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Discount %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 20).forEach { q ->
                        Surface(
                            onClick = { pct = q.toString() },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text("$q%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(shape = MaterialTheme.shapes.medium, onClick = { onApply(pct.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = { onApply(0.0) }) { Text("Clear") } },
    )
}

// =====================================================================
// Item configuration sheet
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemConfigSheet(
    config: TerminalViewModel.ItemConfig,
    imageUrl: String?,
    onDismiss: () -> Unit,
    onAdd: (MenuItemVariantEntity?, List<CartEngine.ModifierSel>, List<CartEngine.AccompanimentSel>, String?, Int) -> Unit,
) {
    var variant by remember(config) { mutableStateOf(config.variants.firstOrNull()) }
    var selectedMods by remember(config) {
        mutableStateOf(
            config.modGroups.flatMap { g -> g.modifiers.filter { it.isDefault } }.map { it.id }.toSet(),
        )
    }
    var selectedAccs by remember(config) {
        mutableStateOf(
            config.accGroups.flatMap { g -> g.options.filter { it.isDefault } }.map { it.id }.toSet(),
        )
    }
    var note by remember(config) { mutableStateOf("") }
    var qty by remember(config) { mutableStateOf(1) }

    val allMods = config.modGroups.flatMap { it.modifiers }
    val allAccs = config.accGroups.flatMap { it.options }
    val unitPrice = (variant?.price ?: (config.item.basePriceMajor ?: 0.0)) +
        allMods.filter { selectedMods.contains(it.id) }.sumOf { it.priceDelta } +
        allAccs.filter { selectedAccs.contains(it.id) }.sumOf { it.priceImpact }

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
                .padding(bottom = 20.dp),
        ) {
            if (imageUrl != null) {
                ItemImage(imageUrl, config.item.name, Modifier.fillMaxWidth().height(160.dp))
                Spacer(Modifier.height(16.dp))
            }
            Text(config.item.name, style = MaterialTheme.typography.headlineSmall)
            config.item.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                Money.format(variant?.price ?: (config.item.basePriceMajor ?: 0.0)),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            if (config.variants.isNotEmpty()) {
                SectionHeader("Size", required = true)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    config.variants.forEach { v ->
                        SelectableRow(
                            title = v.name,
                            trailing = Money.format(v.price),
                            selected = variant?.id == v.id,
                            radio = true,
                            onClick = { variant = v },
                        )
                    }
                }
            }

            config.accGroups.forEach { group ->
                SectionHeader(group.group.name, required = group.group.isRequired)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.options.forEach { opt ->
                        SelectableRow(
                            title = opt.name,
                            trailing = if (opt.priceImpact > 0) "+ ${Money.format(opt.priceImpact)}" else "Included",
                            selected = selectedAccs.contains(opt.id),
                            onClick = {
                                selectedAccs = if (selectedAccs.contains(opt.id)) selectedAccs - opt.id else selectedAccs + opt.id
                            },
                        )
                    }
                }
            }

            config.modGroups.forEach { group ->
                SectionHeader(group.group.name, required = false)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.modifiers.forEach { mod ->
                        SelectableRow(
                            title = mod.name,
                            trailing = when {
                                mod.priceDelta > 0 -> "+ ${Money.format(mod.priceDelta)}"
                                mod.priceDelta < 0 -> "− ${Money.format(-mod.priceDelta)}"
                                else -> ""
                            },
                            selected = selectedMods.contains(mod.id),
                            onClick = {
                                selectedMods = if (selectedMods.contains(mod.id)) selectedMods - mod.id else selectedMods + mod.id
                            },
                        )
                    }
                }
            }

            SectionHeader("Special instructions", required = false)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("E.g. no onions, well done…") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                QuantityStepper(
                    quantity = qty,
                    onDecrement = { if (qty > 1) qty-- },
                    onIncrement = { qty++ },
                    buttonSize = 44.dp,
                )
                PrimaryButton(
                    text = "Add · ${Money.format(unitPrice * qty)}",
                    onClick = {
                        val mods = allMods.filter { selectedMods.contains(it.id) }
                            .map { CartEngine.ModifierSel(it.id, it.name, it.priceDelta) }
                        val accs = allAccs.filter { selectedAccs.contains(it.id) }
                            .map { CartEngine.AccompanimentSel(it.id, it.name, it.priceImpact) }
                        onAdd(variant, mods, accs, note, qty)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String, required: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (required) {
            StatusPill(
                "Required",
                color = MaterialTheme.colorScheme.error,
                container = MaterialTheme.colorScheme.errorContainer,
            )
        }
    }
}

@Composable
internal fun SelectableRow(
    title: String,
    trailing: String,
    selected: Boolean,
    onClick: () -> Unit,
    radio: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = if (radio) CircleShape else RoundedCornerShape(6.dp),
                    )
                    .border(
                        width = if (selected) 0.dp else 1.5.dp,
                        color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                        shape = if (radio) CircleShape else RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = if (trailing.startsWith("+")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
