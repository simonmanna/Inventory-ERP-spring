package com.poscafe.pos.data.repo

import com.poscafe.pos.data.local.entity.LocalCashMovementEntity
import com.poscafe.pos.data.local.entity.LocalRefundEntity
import com.poscafe.pos.data.local.entity.LocalSaleEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * POS report maths, kept free of Android so it is unit-tested on the JVM. The view model loads
 * rows from Room, resolves names/groups/costs into [Lookups], and hands everything here.
 *
 * Line amounts are allocated from the sale total (pro rata to each line's own price), so items,
 * groups and payment methods always add up to the same net sales figure as the receipts —
 * tax, order discounts and accompaniment prices included.
 */
object PosReport {

    /** What the user narrowed the report to; null = everything. Dates are inclusive. */
    data class Filter(
        val from: LocalDate,
        val to: LocalDate,
        val sessionId: String? = null,
        val cashierId: String? = null,
        val method: String? = null,
        val orderType: String? = null,
        val group: String? = null,
    ) {
        val activeCount: Int get() = listOfNotNull(sessionId, cashierId, method, orderType, group).size
    }

    /** Name/group/cost resolution done by the caller (Room lookups). */
    class Lookups(
        val cashierName: (String) -> String,
        val groupOf: (menuItemId: String?, productId: String?) -> String,
        val unitCostOf: (menuItemId: String?, productId: String?) -> Double,
    )

    data class Tender(val method: String, val amount: Double)

    data class Line(
        val name: String,
        val menuItemId: String?,
        val productId: String?,
        val group: String,
        val qty: Double,
        /** Share of the sale total (tax and order discount included). */
        val amount: Double,
        val lineDiscount: Double,
        val cost: Double,
    )

    data class Sale(
        val id: String,
        val number: String,
        val at: Instant,
        val cashierId: String,
        val sessionId: String?,
        val orderType: String,
        val total: Double,
        val tax: Double,
        val tenders: List<Tender>,
        val lines: List<Line>,
        val synced: Boolean,
    ) {
        val primaryMethod: String get() = tenders.maxByOrNull { it.amount }?.method ?: "cash"
    }

    data class Bucket(val key: String, val label: String, val count: Int, val qty: Double, val amount: Double)

    data class ItemRow(
        val name: String,
        val group: String,
        val qty: Double,
        val amount: Double,
        val cost: Double,
        val receipts: Int,
    ) {
        val profit: Double get() = amount - cost
        val margin: Double get() = if (amount > 0) profit / amount * 100 else 0.0
    }

    data class GroupRow(val name: String, val qty: Double, val amount: Double, val cost: Double, val items: List<ItemRow>) {
        val profit: Double get() = amount - cost
    }

    data class DayRow(val date: LocalDate, val count: Int, val amount: Double)

    data class CashFlow(
        val cashSales: Double,
        val cashRefunds: Double,
        val payIns: Double,
        val payOuts: Double,
        val adjustments: Double,
        val movements: List<LocalCashMovementEntity>,
    ) {
        /** Cash the drawer gained over the period (excluding opening floats). */
        val net: Double get() = cashSales - cashRefunds + payIns - payOuts + adjustments
    }

    data class Result(
        val sales: List<Sale>,
        val gross: Double,
        val tax: Double,
        val lineDiscounts: Double,
        val refunds: Double,
        val refundCount: Int,
        val voidCount: Int,
        val itemsSold: Double,
        val cogs: Double,
        val byMethod: List<Bucket>,
        val byCashier: List<Bucket>,
        val byOrderType: List<Bucket>,
        val byHour: List<Bucket>,
        val byDay: List<DayRow>,
        /** [dayOfWeek 0=Mon..6][hour 0..23] → amount. */
        val heat: List<List<Double>>,
        val items: List<ItemRow>,
        val groups: List<GroupRow>,
        val cash: CashFlow,
        val unsynced: Int,
    ) {
        val count: Int get() = sales.size
        val net: Double get() = gross - refunds
        val avgTicket: Double get() = if (sales.isEmpty()) 0.0 else gross / sales.size
        val profit: Double get() = gross - tax - cogs
        val itemsPerSale: Double get() = if (sales.isEmpty()) 0.0 else itemsSold / sales.size
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse a stored sale into report facts. Discarded sales never reach the report. */
    fun toSale(e: LocalSaleEntity, lookups: Lookups): Sale {
        val tenders = parse(e.tendersJson).map {
            val o = it.jsonObject
            Tender(o["method"]?.jsonPrimitive?.content ?: "cash", o["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
        }
        // Stored legs already settle exactly, but older builds kept the overpay on cash: take it off.
        val change = (tenders.sumOf { it.amount } - e.total).coerceAtLeast(0.0)
        var left = change
        val netTenders = tenders.reversed().map { t ->
            if (t.method != "cash" || left <= 0.0) t
            else minOf(t.amount, left).let { cut -> left -= cut; t.copy(amount = t.amount - cut) }
        }.reversed().filter { it.amount > 0.0049 }

        data class Raw(val name: String, val menu: String?, val product: String?, val qty: Double, val list: Double, val price: Double)
        val raw = parse(e.linesJson).map {
            val o = it.jsonObject
            val qty = o["quantity"]?.jsonPrimitive?.doubleOrNull ?: 1.0
            val unit = o["unitPrice"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val mods = (o["modifiers"] as? JsonArray)?.sumOf { m ->
                m.jsonObject["priceDelta"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            } ?: 0.0
            val disc = o["discountPercent"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val list = qty * (unit + mods)
            Raw(
                name = o["description"]?.jsonPrimitive?.content ?: "Item",
                menu = o["menuItemId"]?.jsonPrimitive?.content?.takeIf { s -> s.isNotBlank() },
                product = o["productId"]?.jsonPrimitive?.content?.takeIf { s -> s.isNotBlank() },
                qty = qty,
                list = list,
                price = list * (1 - disc / 100.0),
            )
        }
        val priced = raw.sumOf { it.price }
        val lines = raw.map { r ->
            val share = if (priced > 0) r.price / priced else 1.0 / raw.size
            Line(
                name = r.name,
                menuItemId = r.menu,
                productId = r.product,
                group = lookups.groupOf(r.menu, r.product),
                qty = r.qty,
                amount = e.total * share,
                lineDiscount = r.list - r.price,
                cost = r.qty * lookups.unitCostOf(r.menu, r.product),
            )
        }
        return Sale(
            id = e.id,
            number = e.finalInvoiceNumber ?: e.provisionalNumber,
            at = Instant.ofEpochMilli(e.occurredAt),
            cashierId = e.actorUserId,
            sessionId = e.cashSessionLocalId,
            orderType = e.orderType,
            total = e.total,
            tax = e.taxAmount,
            tenders = netTenders,
            lines = lines,
            synced = e.syncStatus == "pushed",
        )
    }

    private fun parse(s: String) = runCatching { json.parseToJsonElement(s).jsonArray }.getOrNull() ?: JsonArray(emptyList())

    fun build(
        sales: List<LocalSaleEntity>,
        refunds: List<LocalRefundEntity>,
        movements: List<LocalCashMovementEntity>,
        filter: Filter,
        zone: ZoneId,
        lookups: Lookups,
    ): Result {
        val start = filter.from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = filter.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val inRange = { at: Long -> at in start until end }

        val all = sales.filter { it.syncStatus != "discarded" && inRange(it.occurredAt) }.map { toSale(it, lookups) }
        val picked = all.filter { s ->
            (filter.sessionId == null || s.sessionId == filter.sessionId) &&
                (filter.cashierId == null || s.cashierId == filter.cashierId) &&
                (filter.orderType == null || s.orderType == filter.orderType) &&
                (filter.method == null || s.tenders.any { it.method == filter.method }) &&
                (filter.group == null || s.lines.any { it.group == filter.group })
        }
        // Item/group filters narrow the lines too, so "Drinks" shows only drink revenue.
        val lineOk = { l: Line -> filter.group == null || l.group == filter.group }

        // Amount attributed to the filter: whole sale, or just its matching lines / method leg.
        fun valueOf(s: Sale): Double = when {
            filter.group != null -> s.lines.filter(lineOk).sumOf { it.amount }
            filter.method != null -> s.tenders.filter { it.method == filter.method }.sumOf { it.amount }
            else -> s.total
        }

        // A refund can hit a sale rung up before the period; look its tenders up among every sale given.
        val saleById = all.associateBy { it.id }
        val entityById = sales.associateBy { it.id }
        val pickedIds = picked.map { it.id }.toSet()
        val refundRows = refunds.filter { r ->
            inRange(r.occurredAt) && (
                filter.activeCount == 0 || r.saleLocalId in pickedIds ||
                    (filter.activeCount == 1 && filter.sessionId != null && r.cashSessionLocalId == filter.sessionId)
                )
        }

        fun buckets(keyOf: (Sale) -> String, labelOf: (String) -> String) = picked.groupBy(keyOf).map { (k, rows) ->
            Bucket(k, labelOf(k), rows.size, rows.sumOf { s -> s.lines.filter(lineOk).sumOf { it.qty } }, rows.sumOf(::valueOf))
        }.sortedByDescending { it.amount }

        val methodTotals = linkedMapOf<String, Pair<Int, Double>>()
        picked.forEach { s ->
            s.tenders.filter { filter.method == null || it.method == filter.method }.forEach { t ->
                val v = if (filter.group != null) t.amount * (valueOf(s) / s.total.coerceAtLeast(0.01)) else t.amount
                val (c, a) = methodTotals[t.method] ?: (0 to 0.0)
                methodTotals[t.method] = (c + 1) to (a + v)
            }
        }

        val heat = List(7) { MutableList(24) { 0.0 } }
        val hours = MutableList(24) { 0 to 0.0 }
        val days = sortedMapOf<LocalDate, Pair<Int, Double>>()
        var d = filter.from
        while (!d.isAfter(filter.to)) { days[d] = 0 to 0.0; d = d.plusDays(1) }
        picked.forEach { s ->
            val t = s.at.atZone(zone)
            val v = valueOf(s)
            heat[t.dayOfWeek.value - 1][t.hour] += v
            hours[t.hour] = (hours[t.hour].first + 1) to (hours[t.hour].second + v)
            val (c, a) = days[t.toLocalDate()] ?: (0 to 0.0)
            days[t.toLocalDate()] = (c + 1) to (a + v)
        }

        val itemRows = picked.flatMap { s -> s.lines.filter(lineOk).map { s.id to it } }
            .groupBy { (_, l) -> (l.productId ?: l.menuItemId ?: l.name) }
            .map { (_, rows) ->
                val first = rows.first().second
                ItemRow(
                    name = first.name.substringBefore(" · "),
                    group = first.group,
                    qty = rows.sumOf { it.second.qty },
                    amount = rows.sumOf { it.second.amount },
                    cost = rows.sumOf { it.second.cost },
                    receipts = rows.map { it.first }.distinct().size,
                )
            }
            .sortedByDescending { it.amount }
        val groupRows = itemRows.groupBy { it.group }.map { (g, rows) ->
            GroupRow(g, rows.sumOf { it.qty }, rows.sumOf { it.amount }, rows.sumOf { it.cost }, rows)
        }.sortedByDescending { it.amount }

        // Cash drawer view. Movements belong to shifts, not sales, so only the shift filter applies.
        val moves = movements.filter { inRange(it.occurredAt) && (filter.sessionId == null || it.sessionLocalId == filter.sessionId) }
        val cashSales = picked.sumOf { s -> s.tenders.filter { it.method == "cash" }.sumOf { it.amount } }
        val cashRefunds = refundRows.sumOf { r ->
            val s = saleById[r.saleLocalId] ?: entityById[r.saleLocalId]?.let { toSale(it, lookups) } ?: return@sumOf 0.0
            val cashShare = s.tenders.filter { it.method == "cash" }.sumOf { it.amount } / s.total.coerceAtLeast(0.01)
            r.amount * cashShare.coerceIn(0.0, 1.0)
        }

        val pickedLines = picked.flatMap { s -> s.lines.filter(lineOk) }
        return Result(
            sales = picked.sortedByDescending { it.at },
            gross = picked.sumOf(::valueOf),
            tax = picked.sumOf { s -> s.tax * (valueOf(s) / s.total.coerceAtLeast(0.01)) },
            lineDiscounts = pickedLines.sumOf { it.lineDiscount },
            refunds = refundRows.sumOf { it.amount },
            refundCount = refundRows.count { it.type == "refund" },
            voidCount = refundRows.count { it.type == "void" },
            itemsSold = pickedLines.sumOf { it.qty },
            cogs = pickedLines.sumOf { it.cost },
            byMethod = methodTotals.map { (m, ca) -> Bucket(m, methodLabel(m), ca.first, 0.0, ca.second) }.sortedByDescending { it.amount },
            byCashier = buckets({ it.cashierId }, lookups.cashierName),
            byOrderType = buckets({ it.orderType }, ::orderTypeLabel),
            byHour = hours.mapIndexed { h, (c, a) -> Bucket(h.toString(), "%02d".format(h), c, 0.0, a) },
            byDay = days.map { (date, ca) -> DayRow(date, ca.first, ca.second) },
            heat = heat,
            items = itemRows,
            groups = groupRows,
            cash = CashFlow(
                cashSales = cashSales,
                cashRefunds = cashRefunds,
                payIns = moves.filter { it.movementType == "pay_in" }.sumOf { it.amount },
                payOuts = moves.filter { it.movementType == "pay_out" }.sumOf { it.amount },
                adjustments = moves.filter { it.movementType == "adjustment" }.sumOf { it.amount },
                movements = moves.sortedByDescending { it.occurredAt },
            ),
            unsynced = picked.count { !it.synced },
        )
    }

    fun methodLabel(method: String): String = when (method) {
        "cash" -> "Cash"
        "card" -> "Card"
        "mobile_money" -> "Mobile money"
        "bank" -> "Bank"
        "store_credit" -> "Store credit"
        else -> method.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun orderTypeLabel(type: String): String = when (type) {
        "dine_in" -> "Dine-in"
        "takeaway" -> "Takeaway"
        "delivery" -> "Delivery"
        else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
