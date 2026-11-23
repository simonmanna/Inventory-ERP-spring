package com.poscafe.pos.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** A choice in the quick-add form (category, tax). */
data class PickOption(val id: String, val label: String)

/** What the cashier typed in the quick-add form. */
data class QuickAddDraft(
    val name: String,
    val price: Double,
    val cost: Double?,
    val barcode: String?,
    val sku: String?,
    val categoryId: String?,
    val taxId: String?,
    val addToCart: Boolean,
)

/**
 * Quick add from the till (mobile app shell): the few fields needed to sell something new right now — name and
 * price, plus barcode (typed or scanned), category and tax. Retail creates a product; a cafe till ([forMenu]) a menu
 * item, which has no barcode. Everything else is edited later in Products / Menu.
 */
@Composable
fun QuickAddItemDialog(
    forMenu: Boolean,
    initialBarcode: String?,
    categories: List<PickOption>,
    taxes: List<PickOption>,
    initialCategoryId: String?,
    error: String?,
    busy: Boolean,
    showAddToCart: Boolean,
    onSave: (QuickAddDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf(initialBarcode.orEmpty()) }
    var sku by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf(initialCategoryId?.takeIf { id -> categories.any { it.id == id } }) }
    var taxId by remember { mutableStateOf<String?>(null) }
    var addToCart by remember { mutableStateOf(showAddToCart) }
    var scanning by remember { mutableStateOf(false) }
    val priceValue = price.toDoubleOrNull()
    val canSave = !busy && name.isNotBlank() && priceValue != null && priceValue >= 0.0

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(if (forMenu) "New menu item" else "New product", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cost, onValueChange = { cost = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Cost (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
                    )
                }
                if (!forMenu) {
                    OutlinedTextField(
                        value = barcode, onValueChange = { barcode = it.trim() }, label = { Text("Barcode") }, singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { scanning = true }) { Icon(Icons.Outlined.QrCodeScanner, "Scan barcode") }
                        },
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = sku, onValueChange = { sku = it.trim() }, label = { Text("SKU (optional)") }, singleLine = true,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (categories.isNotEmpty()) {
                    ChipPicker("Category", categories, categoryId, noneLabel = "None") { categoryId = it }
                }
                if (taxes.isNotEmpty()) {
                    ChipPicker("Tax", taxes, taxId, noneLabel = "No tax") { taxId = it }
                }
                if (showAddToCart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = addToCart, onCheckedChange = { addToCart = it })
                        Text("Add to the current sale", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                shape = MaterialTheme.shapes.medium,
                onClick = {
                    onSave(
                        QuickAddDraft(
                            name = name.trim(),
                            price = priceValue ?: 0.0,
                            cost = cost.toDoubleOrNull(),
                            barcode = barcode.takeIf { !forMenu && it.isNotBlank() },
                            sku = sku.takeIf { !forMenu && it.isNotBlank() },
                            categoryId = categoryId,
                            taxId = taxId,
                            addToCart = showAddToCart && addToCart,
                        ),
                    )
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )

    if (scanning) {
        BarcodeScannerDialog(
            onDismiss = { scanning = false },
            onBarcodeDetected = { barcode = it },
            title = "Scan the product's barcode",
        )
    }
}

@Composable
private fun ChipPicker(label: String, options: List<PickOption>, selected: String?, noneLabel: String, onPick: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text(noneLabel) })
            options.forEach { o ->
                FilterChip(selected = selected == o.id, onClick = { onPick(o.id) }, label = { Text(o.label) })
            }
        }
    }
}
