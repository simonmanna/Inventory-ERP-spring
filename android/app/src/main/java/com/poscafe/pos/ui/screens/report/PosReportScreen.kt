@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.poscafe.pos.ui.screens.report

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.poscafe.pos.data.repo.PosReport
import com.poscafe.pos.ui.components.Money
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val Sapphire = Brush.linearGradient(listOf(Color(0xFF0B2545), Color(0xFF0C3A66), Color(0xFF0D5689)))
private val Champagne = Color(0xFFD9B872)
private val DAY = DateTimeFormatter.ofPattern("d MMM")
private val DAY_LONG = DateTimeFormatter.ofPattern("EEE d MMM")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm")

private enum class ReportTab(val label: String) {
    Overview("Overview"), Sales("Sales"), Items("Items"), Groups("Item groups"), Shifts("Shifts"), Cash("Cash flow")
}

/**
 * POS report: one screen, six lenses (overview, sales, items, item groups, shifts, cash flow)
 * over the same period + filters. Everything is computed from the till's own database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosReportScreen(onBack: () -> Unit, vm: PosReportViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showFilters by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }
    val r = vm.result
    val f = vm.filter

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("POS Report", style = MaterialTheme.typography.headlineSmall)
                    Text(rangeLabel(f.from, f.to), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BadgedBox(badge = { if (f.activeCount > 0) Badge { Text("${f.activeCount}") } }) {
                    IconButton(onClick = { showFilters = true }) { Icon(Icons.Outlined.FilterList, "Filters") }
                }
                IconButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "POS report ${f.from} – ${f.to}")
                        putExtra(Intent.EXTRA_TEXT, vm.csv())
                    }
                    context.startActivity(Intent.createChooser(intent, "Export report"))
                }) { Icon(Icons.Outlined.IosShare, "Export CSV") }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosReportViewModel.Period.entries.forEach { p ->
                    FilterChip(
                        selected = vm.period == p,
                        onClick = { if (p == PosReportViewModel.Period.Custom) showRange = true else vm.choosePeriod(p) },
                        label = { Text(p.label) },
                        leadingIcon = if (p == PosReportViewModel.Period.Custom) ({ Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp)) }) else null,
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
        if (f.activeCount > 0) item { ActiveFilters(vm) }
        item {
            if (vm.loading && r == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            r?.let { Hero(it, vm.previous, f) }
        }
        item {
            ScrollableTabRow(
                selectedTabIndex = tab,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
            ) {
                ReportTab.entries.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t.label, maxLines = 1) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (r != null) {
            when (ReportTab.entries[tab]) {
                ReportTab.Overview -> overview(r, f)
                ReportTab.Sales -> sales(r, f)
                ReportTab.Items -> items(r)
                ReportTab.Groups -> groups(r, vm)
                ReportTab.Shifts -> shifts(vm)
                ReportTab.Cash -> cash(r, vm)
            }
        }
    }

    if (showFilters) FilterSheet(vm) { showFilters = false }
    if (showRange) RangeDialog(vm) { showRange = false }
}

// ---------------------------------------------------------------------------
// Header pieces
// ---------------------------------------------------------------------------

private fun rangeLabel(from: LocalDate, to: LocalDate): String {
    val today = LocalDate.now()
    return when {
        from == to && from == today -> "Today · ${from.format(DAY_LONG)}"
        from == to -> from.format(DAY_LONG)
        else -> "${from.format(DAY)} – ${to.format(DAY)} · ${Duration.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()).toDays()} days"
    }
}

/** Sapphire hero: net sales, change vs the previous period, and the trend line. */
@Composable
private fun Hero(r: PosReport.Result, prev: PosReport.Result?, f: PosReport.Filter) {
    val singleDay = f.from == f.to
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.background(Sapphire).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Insights, null, tint = Champagne, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("NET SALES", color = Champagne, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                prev?.let { Delta(r.net, it.net, singleDay) }
            }
            Spacer(Modifier.height(6.dp))
            Text(Money.format(r.net), color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HeroStat("Receipts", "${r.count}")
                HeroStat("Avg ticket", Money.bare(r.avgTicket))
                HeroStat("Items", qty(r.itemsSold))
                HeroStat("Profit", shortMoney(r.profit))
            }
            Spacer(Modifier.height(16.dp))
            if (singleDay) {
                val hours = r.byHour.filter { it.count > 0 }.map { it.key.toInt() }
                // One empty hour either side, so a single busy hour reads as a peak, not a cliff.
                val span = if (hours.isEmpty()) (8..20) else ((hours.min() - 1).coerceAtLeast(0)..(hours.max() + 1).coerceAtMost(23))
                TrendChart(
                    values = span.map { r.byHour[it].amount },
                    labels = span.map { "%02d:00".format(it) },
                    color = Champagne,
                    onColor = Color.White,
                )
            } else {
                TrendChart(
                    values = r.byDay.map { it.amount },
                    labels = r.byDay.map { it.date.format(DAY_LONG) },
                    color = Champagne,
                    onColor = Color.White,
                )
            }
        }
    }
}

@Composable
private fun Delta(now: Double, before: Double, singleDay: Boolean) {
    val pct = if (before > 0) (now - before) / before * 100 else null
    val up = now >= before
    Surface(shape = RoundedCornerShape(999.dp), color = Color.White.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (up) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown, null,
                tint = if (up) Color(0xFF6EE7B7) else Color(0xFFFCA5A5), modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                (pct?.let { "%+.0f%%".format(it) } ?: if (now > 0) "new" else "–") + if (singleDay) " vs prev. day" else " vs prev.",
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String) {
    Column {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ActiveFilters(vm: PosReportViewModel) {
    val f = vm.filter
    val o = vm.options
    fun name(list: List<PosReportViewModel.Option>, id: String) = list.firstOrNull { it.id == id }?.label ?: id
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        f.sessionId?.let { FilterPill("Shift: ${name(o.shifts, it)}") { vm.update(f.copy(sessionId = null)) } }
        f.cashierId?.let { FilterPill(name(o.cashiers, it)) { vm.update(f.copy(cashierId = null)) } }
        f.method?.let { FilterPill(PosReport.methodLabel(it)) { vm.update(f.copy(method = null)) } }
        f.orderType?.let { FilterPill(PosReport.orderTypeLabel(it)) { vm.update(f.copy(orderType = null)) } }
        f.group?.let { FilterPill(it) { vm.update(f.copy(group = null)) } }
        TextButton(onClick = vm::clearFilters) { Text("Clear all") }
    }
}

@Composable
private fun FilterPill(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
        trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp)) },
        shape = RoundedCornerShape(999.dp),
    )
}

// ---------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------

private fun LazyListScope.overview(r: PosReport.Result, f: PosReport.Filter) {
    if (r.unsynced > 0) item { UnsyncedNote(r.unsynced) }
    item {
        val margin = if (r.gross - r.tax > 0) r.profit / (r.gross - r.tax) * 100 else 0.0
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Gross sales", Money.format(r.gross), plural(r.count, "receipt"), ChartColors[0], Modifier.weight(1f))
                Kpi("Refunds & voids", Money.format(r.refunds), "${r.refundCount} refunds · ${r.voidCount} voids", ChartColors[4], Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Gross profit", Money.format(r.profit), "%.0f%% margin · cost %s".format(margin, shortMoney(r.cogs)), ChartColors[1], Modifier.weight(1f))
                Kpi("Tax collected", Money.format(r.tax), "Discounts ${shortMoney(r.lineDiscounts)}", ChartColors[2], Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Items sold", qty(r.itemsSold), "%.1f per receipt".format(r.itemsPerSale), ChartColors[3], Modifier.weight(1f))
                Kpi("Cash in drawer", Money.format(r.cash.net), "sales − refunds ± movements", ChartColors[5], Modifier.weight(1f))
            }
        }
    }
    item {
        Section("Payment mix") {
            if (r.byMethod.isEmpty()) Empty() else Row(verticalAlignment = Alignment.CenterVertically) {
                Donut(r.byMethod.map { it.amount }, shortMoney(r.byMethod.sumOf { it.amount }), if (r.byMethod.size == 1) r.byMethod.first().label.lowercase() else "${r.byMethod.size} methods", Modifier.width(130.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val total = r.byMethod.sumOf { it.amount }.coerceAtLeast(0.01)
                    r.byMethod.forEachIndexed { i, m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Swatch(chartColor(i))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${m.count} × · %.0f%%".format(m.amount / total * 100), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(Money.bare(m.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
    item {
        Section("Busiest hours") {
            val peak = r.byHour.maxByOrNull { it.amount }
            if (peak == null || peak.amount <= 0) Empty() else {
                Text(
                    "Peak %s:00–%02d:00 · %d receipts · %s".format(peak.label, (peak.key.toInt() + 1) % 24, peak.count, Money.format(peak.amount)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                HourBars(r.byHour.map { it.amount }, ChartColors[0])
            }
        }
    }
    if (f.from != f.to) item {
        Section("Weekly rhythm", "When the till is busiest, by weekday and hour") { Heatmap(r.heat, ChartColors[1]) }
    }
    item {
        Section("Top sellers") {
            if (r.items.isEmpty()) Empty()
            val top = r.items.take(5)
            val max = top.maxOfOrNull { it.amount }?.coerceAtLeast(0.01) ?: 1.0
            top.forEachIndexed { i, it ->
                RankRow(i + 1, it.name, "${qty(it.qty)} sold · ${it.group}", Money.bare(it.amount), (it.amount / max).toFloat(), chartColor(i))
            }
        }
    }
}

private fun LazyListScope.sales(r: PosReport.Result, f: PosReport.Filter) {
    if (f.from != f.to) item {
        val trading = r.byDay.filter { it.count > 0 }
        val quiet = r.byDay.size - trading.size
        val best = trading.maxByOrNull { it.amount }
        Section(
            "Daily sales",
            "${plural(trading.size, "trading day")}" + (if (quiet > 0) " · $quiet without sales" else "") +
                (best?.let { " · best ${it.date.format(DAY)}" } ?: ""),
        ) {
            if (trading.isEmpty()) Empty()
            val max = trading.maxOfOrNull { it.amount }?.coerceAtLeast(0.01) ?: 1.0
            trading.reversed().forEach { d ->
                Column(Modifier.padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.date.format(DAY_LONG), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(plural(d.count, "receipt"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            Money.bare(d.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.widthIn(min = 88.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    ShareBar((d.amount / max).toFloat(), ChartColors[0])
                }
            }
        }
    }
    item {
        Section("Cashier leaderboard") {
            if (r.byCashier.isEmpty()) Empty()
            val max = r.byCashier.maxOfOrNull { it.amount }?.coerceAtLeast(0.01) ?: 1.0
            r.byCashier.forEachIndexed { i, c ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(chartColor(i).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(initials(c.label), color = chartColor(i), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(c.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(Money.bare(c.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "${plural(c.count, "receipt")} · avg ${Money.bare(c.amount / c.count.coerceAtLeast(1))} · ${qty(c.qty)} items",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        ShareBar((c.amount / max).toFloat(), chartColor(i))
                    }
                }
            }
        }
    }
    item {
        Section("Order types") {
            if (r.byOrderType.isEmpty()) Empty()
            val total = r.byOrderType.sumOf { it.amount }.coerceAtLeast(0.01)
            // One stacked bar, then the legend.
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                r.byOrderType.forEachIndexed { i, b ->
                    Box(Modifier.weight((b.amount / total).toFloat().coerceAtLeast(0.01f)).height(14.dp).background(chartColor(i + 2)))
                }
            }
            Spacer(Modifier.height(10.dp))
            r.byOrderType.forEachIndexed { i, b ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(chartColor(i + 2))
                    Spacer(Modifier.width(8.dp))
                    Text(b.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${b.count} · %.0f%%".format(b.amount / total * 100), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(Money.bare(b.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    item { ReceiptList(r) }
}

@Composable
private fun ReceiptList(r: PosReport.Result) {
    var query by remember { mutableStateOf("") }
    var shown by remember(r) { mutableIntStateOf(25) }
    val rows = r.sales.filter { query.isBlank() || it.number.contains(query, true) || it.lines.any { l -> l.name.contains(query, true) } }
    Section("Receipts", "${r.sales.size} in this view") {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Receipt no. or item") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) Empty()
        rows.take(shown).forEach { s -> ReceiptRow(s) }
        if (rows.size > shown) {
            TextButton(onClick = { shown += 25 }, modifier = Modifier.fillMaxWidth()) { Text("Show ${minOf(25, rows.size - shown)} more") }
        }
    }
}

@Composable
private fun ReceiptRow(s: PosReport.Sale) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .animateContentSize()
            .padding(vertical = 2.dp),
    ) {
        Surface(onClick = { open = !open }, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.number, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        if (!s.synced) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Outlined.CloudOff, "Not synced", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "${s.at.atZone(java.time.ZoneId.systemDefault()).format(DAY_TIME)} · ${PosReport.orderTypeLabel(s.orderType)} · ${s.tenders.joinToString { PosReport.methodLabel(it.method) }}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(Money.bare(s.total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(open) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                s.lines.forEach { l ->
                    Row {
                        Text("${qty(l.qty)} × ${l.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(Money.bare(l.amount), style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                s.tenders.forEach { t ->
                    Row {
                        Text(PosReport.methodLabel(t.method), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Money.bare(t.amount), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

private enum class ItemSort(val label: String) { Revenue("Revenue"), Qty("Qty"), Profit("Profit"), Margin("Margin") }

private fun LazyListScope.items(r: PosReport.Result) {
    item { ItemsSection(r) }
}

@Composable
private fun ItemsSection(r: PosReport.Result) {
    var sort by rememberSaveable { mutableStateOf(ItemSort.Revenue) }
    var query by remember { mutableStateOf("") }
    val rows = r.items
        .filter { query.isBlank() || it.name.contains(query, true) || it.group.contains(query, true) }
        .sortedByDescending {
            when (sort) {
                ItemSort.Revenue -> it.amount
                ItemSort.Qty -> it.qty
                ItemSort.Profit -> it.profit
                ItemSort.Margin -> it.margin
            }
        }
    val max = rows.maxOfOrNull { it.amount }?.coerceAtLeast(0.01) ?: 1.0
    val total = r.items.sumOf { it.amount }.coerceAtLeast(0.01)
    Section("Items sold", "${r.items.size} products · ${qty(r.itemsSold)} units") {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Find an item or group") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ItemSort.entries.forEach { s ->
                FilterChip(
                    selected = sort == s,
                    onClick = { sort = s },
                    label = { Text(s.label, maxLines = 1, softWrap = false) },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (rows.isEmpty()) Empty()
        rows.forEachIndexed { i, it ->
            Column(Modifier.padding(vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RankBadge(i + 1)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${it.group} · ${qty(it.qty)} sold · ${it.receipts} rcpt · %.1f%% of sales".format(it.amount / total * 100),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Money.bare(it.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        if (it.cost > 0) MarginPill(it.margin)
                    }
                }
                Spacer(Modifier.height(5.dp))
                ShareBar((it.amount / max).toFloat(), chartColor(i).takeIf { i < 3 } ?: ChartColors[0].copy(alpha = 0.55f))
            }
        }
    }
}

private fun LazyListScope.groups(r: PosReport.Result, vm: PosReportViewModel) {
    item {
        Section("Item group sales", "${r.groups.size} groups") {
            if (r.groups.isEmpty()) { Empty(); return@Section }
            val total = r.groups.sumOf { it.amount }.coerceAtLeast(0.01)
            Row(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))) {
                r.groups.forEachIndexed { i, g ->
                    Box(Modifier.weight((g.amount / total).toFloat().coerceAtLeast(0.01f)).height(22.dp).background(chartColor(i)))
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.groups.forEachIndexed { i, g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Swatch(chartColor(i))
                        Spacer(Modifier.width(4.dp))
                        Text("${g.name} %.0f%%".format(g.amount / total * 100), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    items(r.groups.withIndex().toList(), key = { it.value.name }) { (i, g) -> GroupCard(i, g, r.groups.sumOf { it.amount }, vm) }
}

@Composable
private fun GroupCard(i: Int, g: PosReport.GroupRow, total: Double, vm: PosReportViewModel) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).animateContentSize(),
        onClick = { open = !open },
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(4.dp).background(chartColor(i)))
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${g.items.size} items · ${qty(g.qty)} sold" + if (g.cost > 0) " · profit ${shortMoney(g.profit)}" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Money.bare(g.amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = chartColor(i))
                        Text("%.1f%% of sales".format(g.amount / total.coerceAtLeast(0.01) * 100), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (open) {
                    Spacer(Modifier.height(10.dp))
                    val max = g.items.maxOfOrNull { it.amount }?.coerceAtLeast(0.01) ?: 1.0
                    g.items.forEach { it ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row {
                                Text(it.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text("${qty(it.qty)} × ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(Money.bare(it.amount), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(3.dp))
                            ShareBar((it.amount / max).toFloat(), chartColor(i).copy(alpha = 0.7f))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { vm.update(vm.filter.copy(group = g.name)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Filter report to ${g.name}")
                    }
                }
            }
        }
    }
}

private fun LazyListScope.shifts(vm: PosReportViewModel) {
    if (vm.shifts.isEmpty()) item { Section("Shifts") { Empty("No shifts in this period") } }
    items(vm.shifts, key = { it.session.id }) { s -> ShiftCard(s, vm) }
}

@Composable
private fun ShiftCard(s: PosReportViewModel.ShiftRow, vm: PosReportViewModel) {
    val zone = vm.zone
    val open = s.session.status == "open"
    val opened = Instant.ofEpochMilli(s.session.openedAt).atZone(zone)
    val closed = s.session.closedAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
    val mins = Duration.between(opened, closed ?: java.time.ZonedDateTime.now(zone)).toMinutes()
    val z = s.z
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (open) ChartColors[0] else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Sapphire), contentAlignment = Alignment.Center) {
                    Text(initials(s.cashier), color = Champagne, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.register, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.cashier, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${opened.format(DAY_TIME)} → ${closed?.format(if (closed.toLocalDate() == opened.toLocalDate()) TIME else DAY_TIME) ?: "now"} · ${mins / 60}h ${mins % 60}m",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(shape = RoundedCornerShape(999.dp), color = if (open) ChartColors[0].copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        if (open) "● Open" else "Closed",
                        color = if (open) ChartColors[0] else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("Sales", Money.bare(z?.salesTotal ?: 0.0), plural(z?.salesCount ?: 0, "receipt"), Modifier.weight(1f))
                MiniStat("Float", Money.bare(s.session.openingFloat), "opening", Modifier.weight(1f))
                MiniStat("Refunds", Money.bare(z?.refunds ?: 0.0), "in/out ${shortMoney(z?.payIn ?: 0.0)}/${shortMoney(z?.payOut ?: 0.0)}", Modifier.weight(1f))
            }
            if (s.byMethod.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.byMethod.forEachIndexed { i, m ->
                        Surface(shape = RoundedCornerShape(999.dp), color = chartColor(i).copy(alpha = 0.12f)) {
                            Text("${m.label} ${Money.bare(m.amount)}", color = chartColor(i), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
            }
            if (z != null) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Expected cash", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Money.bare(z.expectedCash), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Counted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(z.counted?.let { Money.bare(it) } ?: "—", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text("Variance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val v = z.variance
                            Text(
                                v?.let { (if (it > 0) "+" else "") + Money.bare(it) } ?: "—",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                color = when {
                                    v == null -> MaterialTheme.colorScheme.onSurface
                                    kotlin.math.abs(v) < 0.5 -> ChartColors[0]
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
                s.session.varianceReason?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Reason: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (vm.filter.sessionId != s.session.id) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.update(vm.filter.copy(sessionId = s.session.id)) }) { Text("Report on this shift only") }
            }
        }
    }
}

private fun LazyListScope.cash(r: PosReport.Result, vm: PosReportViewModel) {
    val c = r.cash
    val shift = vm.filter.sessionId?.let { id -> vm.shifts.firstOrNull { it.session.id == id } }
    item {
        Section("Cash flow", if (shift != null) "Drawer for ${shift.register} · ${shift.cashier}" else "Drawer cash across all shifts in the period") {
            val steps = buildList {
                if (shift != null) add(WaterStep("Float", shift.session.openingFloat, total = true))
                add(WaterStep("Cash sales", c.cashSales))
                add(WaterStep("Refunds", -c.cashRefunds))
                add(WaterStep("Pay-ins", c.payIns))
                add(WaterStep("Pay-outs", -c.payOuts))
                if (c.adjustments != 0.0) add(WaterStep("Adjust", c.adjustments))
                add(WaterStep(if (shift != null) "Drawer" else "Net", (shift?.session?.openingFloat ?: 0.0) + c.net, total = true))
            }
            Waterfall(steps)
            Spacer(Modifier.height(14.dp))
            CashLine("Cash sales (net of change)", c.cashSales, ChartColors[0])
            CashLine("Cash refunds", -c.cashRefunds, ChartColors[4])
            CashLine("Pay-ins", c.payIns, ChartColors[0])
            CashLine("Pay-outs & drawer expenses", -c.payOuts, ChartColors[4])
            if (c.adjustments != 0.0) CashLine("Adjustments", c.adjustments, ChartColors[2])
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            CashLine("Net cash movement", c.net, MaterialTheme.colorScheme.onSurface, bold = true)
            shift?.z?.let { z ->
                CashLine("Expected in drawer", z.expectedCash, MaterialTheme.colorScheme.onSurface, bold = true)
                z.counted?.let { CashLine("Counted at close", it, MaterialTheme.colorScheme.onSurface) }
                z.variance?.let { CashLine("Over / short", it, if (kotlin.math.abs(it) < 0.5) ChartColors[0] else MaterialTheme.colorScheme.error, bold = true) }
            }
            val nonCash = r.byMethod.filter { it.key != "cash" }
            if (nonCash.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Non-cash takings (not in the drawer)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                nonCash.forEachIndexed { i, m -> CashLine(m.label, m.amount, chartColor(i + 1)) }
            }
        }
    }
    item {
        Section("Drawer movements", "${c.movements.size} pay-ins, pay-outs and adjustments") {
            if (c.movements.isEmpty()) Empty("No pay-ins or pay-outs")
            c.movements.forEach { m ->
                val inflow = m.movementType == "pay_in" || (m.movementType == "adjustment" && m.amount >= 0)
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background((if (inflow) ChartColors[0] else ChartColors[4]).copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (inflow) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown, null,
                            tint = if (inflow) ChartColors[0] else ChartColors[4], modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.reason?.takeIf { it.isNotBlank() } ?: m.movementType.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Instant.ofEpochMilli(m.occurredAt).atZone(vm.zone).format(DAY_TIME)} · ${m.movementType.replace('_', '-')}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        (if (m.movementType == "pay_out") "−" else "+") + Money.bare(m.amount),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = if (inflow) ChartColors[0] else ChartColors[4],
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, hint: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row {
            Box(Modifier.width(4.dp).height(86.dp).background(accent))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hint, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RankRow(rank: Int, name: String, sub: String, amount: String, fraction: Float, color: Color) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RankBadge(rank)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        ShareBar(fraction, color)
    }
}

/** Gold, silver, bronze for the podium; plain after that. */
@Composable
private fun RankBadge(rank: Int) {
    val (bg, fg) = when (rank) {
        1 -> Champagne to Color(0xFF3B2F0B)
        2 -> Color(0xFFCBD5E1) to Color(0xFF1E293B)
        3 -> Color(0xFFE7B58A) to Color(0xFF4A2A10)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(28.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text("$rank", color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MarginPill(margin: Double) {
    val c = when { margin >= 50 -> ChartColors[0]; margin >= 20 -> ChartColors[2]; else -> ChartColors[4] }
    Surface(shape = RoundedCornerShape(999.dp), color = c.copy(alpha = 0.14f)) {
        Text("%.0f%% margin".format(margin), color = c, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

@Composable
private fun CashLine(label: String, amount: Double, color: Color, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(
            (if (amount > 0 && !bold) "+" else "") + Money.format(amount),
            style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, color = color,
        )
    }
}

@Composable
private fun UnsyncedNote(count: Int) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("$count receipt${if (count == 1) "" else "s"} not synced yet — included here, not yet in the back office.", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Empty(text: String = "Nothing sold in this view") {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}

private fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

private fun qty(v: Double) = if (v % 1.0 == 0.0) "%,.0f".format(v) else "%,.2f".format(v)

// ---------------------------------------------------------------------------
// Filter sheet + custom range
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(vm: PosReportViewModel, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(vm.filter) }
    val o = vm.options
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Filter report", style = MaterialTheme.typography.headlineSmall)
            Text("Combine any of these; the period stays as picked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterGroup("Shift", o.shifts, draft.sessionId) { draft = draft.copy(sessionId = it) }
            FilterGroup("Cashier", o.cashiers, draft.cashierId) { draft = draft.copy(cashierId = it) }
            FilterGroup("Payment method", o.methods, draft.method) { draft = draft.copy(method = it) }
            FilterGroup("Order type", o.orderTypes, draft.orderType) { draft = draft.copy(orderType = it) }
            FilterGroup("Item group", o.groups, draft.group) { draft = draft.copy(group = it) }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { draft = draft.copy(sessionId = null, cashierId = null, method = null, orderType = null, group = null) },
                    modifier = Modifier.weight(1f),
                ) { Text("Reset") }
                Button(onClick = { vm.update(draft); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, options: List<PosReportViewModel.Option>, selected: String?, onPick: (String?) -> Unit) {
    if (options.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text("All") }, shape = RoundedCornerShape(999.dp))
        options.forEach { opt ->
            FilterChip(
                selected = selected == opt.id,
                onClick = { onPick(if (selected == opt.id) null else opt.id) },
                label = { Text(opt.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = RoundedCornerShape(999.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDialog(vm: PosReportViewModel, onDismiss: () -> Unit) {
    fun millis(d: LocalDate) = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = millis(vm.filter.from),
        initialSelectedEndDateMillis = millis(vm.filter.to),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis ?: return@TextButton
                    vm.setRange(date(start), date(state.selectedEndDateMillis ?: start))
                    onDismiss()
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f), title = { Text("Report period", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

