package com.poscafe.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.poscafe.pos.ui.components.Money

/** Phase I6 terminal dialogs: the product-variant picker and the kitchen-void approval. */
@Composable
fun TerminalIntegrityDialogs(vm: TerminalViewModel) {
    vm.variantPick?.let { pick ->
        AlertDialog(
            onDismissRequest = { vm.dismissVariantPick() },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(pick.product.name, style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pick.variants.forEach { v ->
                        OutlinedButton(
                            onClick = { vm.onVariantPicked(pick.product, v) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(v.name)
                                Text(Money.format(v.price))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.dismissVariantPick() }) { Text("Cancel") } },
        )
    }

    vm.voidRequest?.let { req ->
        var reason by remember(req) { mutableStateOf("") }
        var pin by remember(req) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.cancelVoid() },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("Void sent item", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val qty = if (req.quantity % 1.0 == 0.0) req.quantity.toLong().toString() else req.quantity.toString()
                    Text(
                        "$qty × ${req.line.name} was already sent to the kitchen. Voiding it records the food as waste " +
                            "and needs a reason and a manager PIN.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text("Manager PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    vm.voidMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmKitchenVoid(reason, pin) },
                    enabled = pin.length >= 4 && reason.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Void") }
            },
            dismissButton = { TextButton(onClick = { vm.cancelVoid() }) { Text("Cancel") } },
        )
    }
}
