package com.poscafe.pos.ui.screens.report

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.data.local.dao.CashSessionDao
import com.poscafe.pos.data.local.dao.MenuDao
import com.poscafe.pos.data.local.dao.ProductDao
import com.poscafe.pos.data.local.dao.RefundDao
import com.poscafe.pos.data.local.dao.RegisterDao
import com.poscafe.pos.data.local.dao.SaleDao
import com.poscafe.pos.data.local.dao.StaffDao
import com.poscafe.pos.data.local.entity.LocalCashSessionEntity
import com.poscafe.pos.data.repo.CashSessionRepository
import com.poscafe.pos.data.repo.PosReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * POS report: shift, sales, items, item groups and cash flow over a period, from the till's own
 * database (works offline; covers what this device sold). Maths lives in [PosReport].
 */
@HiltViewModel
class PosReportViewModel @Inject constructor(
    private val saleDao: SaleDao,
    private val refundDao: RefundDao,
    private val cashDao: CashSessionDao,
    private val menuDao: MenuDao,
    private val productDao: ProductDao,
    private val staffDao: StaffDao,
    private val registerDao: RegisterDao,
    private val sessions: CashSessionRepository,
) : ViewModel() {

    enum class Period(val label: String) {
        Today("Today"), Yesterday("Yesterday"), Week("7 days"), Month("This month"), Days30("30 days"), Custom("Custom")
    }

    data class Option(val id: String, val label: String)

    data class Options(
        val shifts: List<Option> = emptyList(),
        val cashiers: List<Option> = emptyList(),
        val methods: List<Option> = emptyList(),
        val orderTypes: List<Option> = emptyList(),
        val groups: List<Option> = emptyList(),
    )

    data class ShiftRow(
        val session: LocalCashSessionEntity,
        val register: String,
        val cashier: String,
        val z: CashSessionRepository.LocalZReport?,
        val byMethod: List<PosReport.Bucket>,
    )

    val zone: ZoneId = ZoneId.systemDefault()

    var period by mutableStateOf(Period.Today); private set
    var filter by mutableStateOf(PosReport.Filter(LocalDate.now(zone), LocalDate.now(zone))); private set
    var result by mutableStateOf<PosReport.Result?>(null); private set
    /** Same filters over the equal-length window just before; null while loading. */
    var previous by mutableStateOf<PosReport.Result?>(null); private set
    var shifts by mutableStateOf<List<ShiftRow>>(emptyList()); private set
    var options by mutableStateOf(Options()); private set
    var loading by mutableStateOf(true); private set

    private var job: Job? = null

    init { load() }

    fun choosePeriod(p: Period) {
        val today = LocalDate.now(zone)
        val (from, to) = when (p) {
            Period.Today -> today to today
            Period.Yesterday -> today.minusDays(1) to today.minusDays(1)
            Period.Week -> today.minusDays(6) to today
            Period.Month -> today.withDayOfMonth(1) to today
            Period.Days30 -> today.minusDays(29) to today
            Period.Custom -> filter.from to filter.to
        }
        period = p
        // A shift picked for another period may not exist in this one.
        filter = filter.copy(from = from, to = to, sessionId = null)
        load()
    }

    fun setRange(from: LocalDate, to: LocalDate) {
        period = Period.Custom
        filter = filter.copy(from = minOf(from, to), to = maxOf(from, to), sessionId = null)
        load()
    }

    fun update(f: PosReport.Filter) { filter = f; load() }

    fun clearFilters() = update(filter.copy(sessionId = null, cashierId = null, method = null, orderType = null, group = null))

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            val f = filter
            val start = f.from.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = f.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val days = ChronoUnit.DAYS.between(f.from, f.to) + 1
            val prev = f.copy(from = f.from.minusDays(days), to = f.from.minusDays(1))
            val prevStart = prev.from.atStartOfDay(zone).toInstant().toEpochMilli()

            val lookups = lookups()
            // Sales back to 60 days before the window too: refunds in the window may hit them.
            val sales = saleDao.between(minOf(prevStart, start - 60L * 86_400_000), end)
            val refunds = refundDao.between(prevStart, end)
            val moves = cashDao.movementsSince(prevStart).filter { it.occurredAt < end }

            result = PosReport.build(sales, refunds, moves, f, zone, lookups)
            previous = PosReport.build(sales, refunds, moves, prev, zone, lookups)

            val unfiltered = PosReport.build(sales, refunds, moves, f.copy(sessionId = null, cashierId = null, method = null, orderType = null, group = null), zone, lookups)
            val registers = registerDao.all().associateBy { it.id }
            val sessionRows = cashDao.overlapping(start, end)
            options = Options(
                shifts = sessionRows.map { Option(it.id, shiftLabel(it, registers[it.cashRegisterId]?.name, lookups.cashierName(it.actorUserId))) },
                cashiers = unfiltered.byCashier.map { Option(it.key, it.label) },
                methods = unfiltered.byMethod.map { Option(it.key, it.label) },
                orderTypes = unfiltered.byOrderType.map { Option(it.key, it.label) },
                groups = unfiltered.groups.map { Option(it.name, it.name) },
            )
            shifts = sessionRows
                .filter { (f.sessionId == null || it.id == f.sessionId) && (f.cashierId == null || it.actorUserId == f.cashierId) }
                .map { s ->
                    val shiftSales = saleDao.forSession(s.id).map { PosReport.toSale(it, lookups) }
                    ShiftRow(
                        session = s,
                        register = registers[s.cashRegisterId]?.let { it.name ?: it.code } ?: "Register",
                        cashier = lookups.cashierName(s.actorUserId),
                        z = sessions.zReport(s.id),
                        byMethod = shiftSales.flatMap { it.tenders }.groupBy { it.method }
                            .map { (m, t) -> PosReport.Bucket(m, PosReport.methodLabel(m), t.size, 0.0, t.sumOf { it.amount }) }
                            .sortedByDescending { it.amount },
                    )
                }
            loading = false
        }
    }

    private suspend fun lookups(): PosReport.Lookups {
        val staff = staffDao.all().associate { it.id to listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { it.email } }
        val menuCategories = menuDao.allCategoriesIncludingInactive().first().associate { it.id to it.name }
        val menuItems = menuDao.allItemsIncludingUnavailable().first().associateBy { it.id }
        val menuCost = menuDao.allLocalMeta().first().associate { it.menuItemId to (it.costMajor ?: 0.0) }
        val products = productDao.all().first().associateBy { it.id }
        // Menu and product categories often share a name in different case ("PASTRIES" / "Pastries"):
        // one group, shown in its mixed-case spelling when there is one.
        val spelling = (menuCategories.values + products.values.mapNotNull { it.categoryName })
            .groupBy { it.trim().lowercase() }
            .mapValues { (_, names) -> names.firstOrNull { n -> n != n.uppercase() } ?: names.first() }
        fun group(name: String?) = name?.takeIf { it.isNotBlank() }?.let { spelling[it.trim().lowercase()] ?: it.trim() }
        return PosReport.Lookups(
            cashierName = { id -> staff[id] ?: "Unknown" },
            groupOf = { menuId, productId ->
                group(productId?.let { products[it]?.categoryName })
                    ?: group(menuId?.let { id -> menuItems[id]?.categoryId?.let(menuCategories::get) })
                    ?: "Other"
            },
            unitCostOf = { menuId, productId ->
                productId?.let { products[it]?.costPrice } ?: menuId?.let(menuCost::get) ?: 0.0
            },
        )
    }

    private fun shiftLabel(s: LocalCashSessionEntity, register: String?, cashier: String): String =
        "${register ?: "Register"} · $cashier · ${SHIFT_TIME.format(Instant.ofEpochMilli(s.openedAt).atZone(zone))}"

    /** Flat CSV of every section, for the share sheet. */
    fun csv(): String {
        val r = result ?: return ""
        val sb = StringBuilder("Section,Label,Count,Qty,Amount\n")
        fun row(section: String, label: String, count: Any = "", qty: Any = "", amount: Any = "") =
            sb.append(section).append(',').append(esc(label)).append(',').append(count).append(',').append(qty).append(',').append(amount).append('\n')
        row("Period", "${filter.from} to ${filter.to}")
        row("Summary", "Gross sales", r.count, r.itemsSold, r.gross)
        row("Summary", "Refunds", r.refundCount + r.voidCount, "", r.refunds)
        row("Summary", "Net sales", "", "", r.net)
        row("Summary", "Tax", "", "", r.tax)
        row("Summary", "Cost of goods", "", "", r.cogs)
        row("Summary", "Gross profit", "", "", r.profit)
        r.byMethod.forEach { row("Payment", it.label, it.count, "", it.amount) }
        r.byCashier.forEach { row("Cashier", it.label, it.count, it.qty, it.amount) }
        r.byOrderType.forEach { row("Order type", it.label, it.count, it.qty, it.amount) }
        r.byDay.forEach { row("Day", it.date.toString(), it.count, "", it.amount) }
        r.byHour.filter { it.count > 0 }.forEach { row("Hour", it.label + ":00", it.count, "", it.amount) }
        r.groups.forEach { row("Item group", it.name, "", it.qty, it.amount) }
        r.items.forEach { row("Item", it.name, it.receipts, it.qty, it.amount) }
        row("Cash flow", "Cash sales", "", "", r.cash.cashSales)
        row("Cash flow", "Cash refunds", "", "", -r.cash.cashRefunds)
        row("Cash flow", "Pay-ins", "", "", r.cash.payIns)
        row("Cash flow", "Pay-outs", "", "", -r.cash.payOuts)
        row("Cash flow", "Adjustments", "", "", r.cash.adjustments)
        shifts.forEach { s ->
            row("Shift", "${s.register} · ${s.cashier}", s.z?.salesCount ?: "", "", s.z?.salesTotal ?: "")
            s.z?.let { z ->
                row("Shift", "  expected cash", "", "", z.expectedCash)
                z.counted?.let { row("Shift", "  counted", "", "", it) }
                z.variance?.let { row("Shift", "  variance", "", "", it) }
            }
        }
        return sb.toString()
    }

    private fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s

    private companion object {
        val SHIFT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")
    }
}
