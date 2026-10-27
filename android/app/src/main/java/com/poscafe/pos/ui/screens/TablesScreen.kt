package com.poscafe.pos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poscafe.pos.data.local.entity.PosTableEntity
import com.poscafe.pos.ui.components.EmptyState
import com.poscafe.pos.ui.components.StatusDot
import com.poscafe.pos.ui.components.StatusPill
import com.poscafe.pos.ui.components.pressScale

/**
 * Floor view. Table statuses are the last-synced server truth (read-only on
 * the device); picking a table starts (or resumes) its dine-in order on the
 * Sell tab.
 */
@Composable
fun TablesScreen(vm: TerminalViewModel, onTableChosen: () -> Unit, onMenu: (() -> Unit)? = null) {
    val tables by vm.tables.collectAsStateWithLifecycle()
    val selected by vm.selectedTable.collectAsStateWithLifecycle()
    val openTabIds by vm.openTabTableIds.collectAsStateWithLifecycle()
    TableFloor(
        tables = tables,
        selectedId = selected?.id,
        openTabTableIds = openTabIds.toSet(),
        onSelect = { table ->
            // Through selectTable so the current tab is banked and this table's tab loaded.
            vm.selectTable(table)
            onTableChosen()
        },
        onMenu = onMenu,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    )
}

/**
 * The table grid. In cafe mode the Sell tab shows this instead of the menu
 * until a table is picked (same as the web terminal); [onTakeaway] offers
 * the explicit no-table alternative.
 */
@Composable
fun TableFloor(
    tables: List<PosTableEntity>,
    selectedId: String?,
    openTabTableIds: Set<String>,
    onSelect: (PosTableEntity) -> Unit,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    onTakeaway: (() -> Unit)? = null,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                onMenu?.let {
                    IconButton(onClick = it) { Icon(Icons.Filled.Menu, "Menu") }
                }
                Column {
                    Text("Tables", style = MaterialTheme.typography.headlineSmall)
                    if (onTakeaway != null) {
                        Text(
                            "Pick a table to start the order",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            onTakeaway?.let {
                OutlinedButton(onClick = it) {
                    Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Takeaway")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot("Available", MaterialTheme.colorScheme.primary)
            LegendDot("Occupied", MaterialTheme.colorScheme.error)
            LegendDot("Reserved", com.poscafe.pos.ui.theme.LocalPosAccents.current.warning)
        }
        if (tables.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.TableRestaurant,
                title = "No tables synced",
                subtitle = "Create tables in the back office; they appear here after a sync.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(tables, key = { it.id }) { table ->
                    TableCard(
                        table = table,
                        selected = selectedId == table.id,
                        openTab = table.id in openTabTableIds,
                        onClick = { onSelect(table) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusDot(color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TableCard(table: PosTableEntity, selected: Boolean, openTab: Boolean, onClick: () -> Unit) {
    // An unsettled tab on this till is fresher than the last-synced server status.
    val statusColor = if (openTab) MaterialTheme.colorScheme.error else tableStatusColor(table.status)
    val statusLabel = if (openTab) "Open tab" else table.status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.pressScale(interaction),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Table ${table.number}", style = MaterialTheme.typography.titleSmall)
                StatusDot(statusColor, size = 10.dp)
            }
            table.name?.takeIf { it.isNotBlank() && it != "Table ${table.number}" }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } ?: Box(Modifier.height(2.dp).alpha(0f))
            StatusPill(statusLabel, color = statusColor, container = statusColor.copy(alpha = 0.12f))
        }
    }
}
