package com.poscafe.pos

import com.poscafe.pos.data.local.entity.LocalCashMovementEntity
import com.poscafe.pos.data.local.entity.LocalRefundEntity
import com.poscafe.pos.data.local.entity.LocalSaleEntity
import com.poscafe.pos.data.repo.PosReport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PosReportTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 11, 3)
    private fun at(hour: Int, d: LocalDate = day) = d.atTime(hour, 15).toInstant(zone).toEpochMilli()

    private val lookups = PosReport.Lookups(
        cashierName = { mapOf("u1" to "Sarah", "u2" to "John")[it] ?: "Unknown" },
        groupOf = { menu, product -> if (product == "p-tea" || menu == "m-tea") "Drinks" else "Food" },
        unitCostOf = { menu, product -> if (product == "p-tea") 1000.0 else 0.0 },
    )

    private fun sale(
        id: String,
        hour: Int,
        total: Double,
        lines: String,
        tenders: String,
        cashier: String = "u1",
        session: String? = "s1",
        status: String = "pushed",
        orderType: String = "takeaway",
        date: LocalDate = day,
    ) = LocalSaleEntity(
        id = id, occurredAt = at(hour, date), actorUserId = cashier, cashSessionLocalId = session, tableId = null,
        orderType = orderType, provisionalNumber = "D1-$id", subtotal = total, taxAmount = total * 0.1, total = total,
        tendersJson = tenders, linesJson = lines, syncStatus = status, serverInvoiceId = null, finalInvoiceNumber = null,
        lastError = null,
    )

    private val tea2 = """[{"productId":"p-tea","description":"Tea","quantity":2,"unitPrice":5000}]"""
    private val teaAndToast = """[{"productId":"p-tea","description":"Tea","quantity":1,"unitPrice":5000},
        {"menuItemId":"m-toast","description":"Toast","quantity":1,"unitPrice":15000,"discountPercent":0}]"""

    private val sales = listOf(
        sale("a", 9, 10000.0, tea2, """[{"method":"cash","amount":10000}]"""),
        sale("b", 12, 20000.0, teaAndToast, """[{"method":"card","amount":5000},{"method":"cash","amount":15000}]""", cashier = "u2", orderType = "dine_in"),
        sale("c", 12, 9999.0, tea2, """[{"method":"cash","amount":9999}]""", status = "discarded"),
        sale("d", 10, 7000.0, tea2, """[{"method":"cash","amount":7000}]""", date = day.minusDays(1)),
    )
    private val refunds = listOf(
        LocalRefundEntity("r1", "a", null, "refund", "spilt", 10000.0, null, "s1", at(13), "pushed", null),
    )
    private val moves = listOf(
        LocalCashMovementEntity("m1", "s1", "pay_out", 3000.0, "Milk", at(11), "pushed"),
        LocalCashMovementEntity("m2", "s1", "pay_in", 1000.0, null, at(14), "pushed"),
    )

    private fun build(f: PosReport.Filter = PosReport.Filter(day, day)) = PosReport.build(sales, refunds, moves, f, zone, lookups)

    @Test
    fun `totals skip discarded and out-of-range sales`() {
        val r = build()
        assertEquals(2, r.count)
        assertEquals(30000.0, r.gross, 0.001)
        assertEquals(10000.0, r.refunds, 0.001)
        assertEquals(20000.0, r.net, 0.001)
        assertEquals(4.0, r.itemsSold, 0.001)
    }

    @Test
    fun `items and groups reconcile with gross`() {
        val r = build()
        assertEquals(r.gross, r.items.sumOf { it.amount }, 0.001)
        assertEquals(r.gross, r.groups.sumOf { it.amount }, 0.001)
        val drinks = r.groups.first { it.name == "Drinks" }
        assertEquals(15000.0, drinks.amount, 0.001)
        assertEquals(3.0, drinks.qty, 0.001)
        assertEquals(3000.0, drinks.cost, 0.001)
    }

    @Test
    fun `payment methods and cash flow`() {
        val r = build()
        assertEquals(25000.0, r.byMethod.first { it.key == "cash" }.amount, 0.001)
        assertEquals(5000.0, r.byMethod.first { it.key == "card" }.amount, 0.001)
        // Refund of an all-cash sale leaves the drawer in full.
        assertEquals(10000.0, r.cash.cashRefunds, 0.001)
        assertEquals(25000.0 - 10000.0 - 3000.0 + 1000.0, r.cash.net, 0.001)
    }

    @Test
    fun `hour and cashier buckets`() {
        val r = build()
        assertEquals(1, r.byHour[9].count)
        assertEquals(20000.0, r.byHour[12].amount, 0.001)
        assertEquals("John", r.byCashier.first().label)
    }

    @Test
    fun `group filter narrows lines and money`() {
        val r = build(PosReport.Filter(day, day, group = "Food"))
        assertEquals(1, r.count)
        assertEquals(15000.0, r.gross, 0.001)
        assertEquals(listOf("Toast"), r.items.map { it.name })
        // The refunded sale had no food, so its refund is out of this view.
        assertEquals(0.0, r.refunds, 0.001)
    }

    @Test
    fun `method filter counts only that leg`() {
        val r = build(PosReport.Filter(day, day, method = "card"))
        assertEquals(1, r.count)
        assertEquals(5000.0, r.gross, 0.001)
    }

    @Test
    fun `multi-day range fills empty days`() {
        val r = build(PosReport.Filter(day.minusDays(2), day))
        assertEquals(3, r.byDay.size)
        assertEquals(0.0, r.byDay[0].amount, 0.001)
        assertEquals(7000.0, r.byDay[1].amount, 0.001)
        assertEquals(3, r.count)
    }
}
