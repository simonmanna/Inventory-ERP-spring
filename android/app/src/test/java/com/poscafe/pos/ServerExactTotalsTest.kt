package com.poscafe.pos

import com.poscafe.pos.data.repo.CartEngine
import com.poscafe.pos.data.repo.SaleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Phase I6: the till computes the total the way the server does (tax rounded
 * per line to the currency's decimals, inclusive tax backed out as
 * amount − tax), because it sends that total as `expectedTotal` and pays legs
 * that must sum to it exactly.
 */
class ServerExactTotalsTest {

    private fun line(base: Double, qty: Double = 1.0, discount: Double = 0.0, rate: Double = 0.0, inclusive: Boolean = false) =
        CartEngine.CartLine(
            lineId = "l-$base-$qty",
            productId = "p1",
            name = "Test",
            quantity = qty,
            baseUnitPrice = base,
            discountPercent = discount,
            taxRatePercent = rate,
            taxInclusive = inclusive,
        )

    @Test
    fun `inclusive tax is rounded to the currency and the price is kept`() {
        val t = CartEngine.totals(listOf(line(1000.0, rate = 18.0, inclusive = true)), scale = 2)
        assertEquals(152.54, t.taxTotal, 0.0)
        assertEquals(1000.0, t.total, 0.0)
    }

    @Test
    fun `exclusive tax rounds per line, the amount keeps six decimals`() {
        // gross 99.99, 10% off = 9.999 → amount 89.991; tax 14.39856 → 14.40
        val t = CartEngine.totals(listOf(line(33.33, qty = 3.0, discount = 10.0, rate = 16.0)), scale = 2)
        assertEquals(14.40, t.taxTotal, 0.0)
        assertEquals(104.391, t.total, 0.0)
    }

    @Test
    fun `zero-decimal currency rounds tax to whole units`() {
        val t = CartEngine.totals(listOf(line(999.0, rate = 18.0)), scale = 0)
        assertEquals(180.0, t.taxTotal, 0.0)
        assertEquals(1179.0, t.total, 0.0)
    }

    @Test
    fun `no currency keeps six decimals of tax`() {
        val t = CartEngine.totals(listOf(line(999.0, rate = 18.0)), scale = -1)
        assertEquals(179.82, t.taxTotal, 0.0)
        assertEquals(1178.82, t.total, 0.0)
    }

    @Test
    fun `per-line rounding is summed, not the cart`() {
        // Each line: 0.05 × 10% = 0.005 → 0.01 (HALF_UP); cart-level rounding would give 0.01 for both.
        val t = CartEngine.totals(listOf(line(0.05, rate = 10.0), line(0.05, qty = 1.0000001, rate = 10.0)), scale = 2)
        assertEquals(0.02, t.taxTotal, 0.000001)
    }

    @Test
    fun `cash overpay becomes change, legs settle the total exactly`() {
        val legs = SaleRepository.settleExactly(
            listOf(SaleRepository.Tender("card", 50.0), SaleRepository.Tender("cash", 100.0)), 104.391, 0.01,
        )
        assertEquals(listOf("card", "cash"), legs.map { it.method })
        assertEquals(104.391, legs.sumOf { it.amount }, 0.0000001)
        assertEquals(54.391, legs[1].amount, 0.0000001)
    }

    @Test
    fun `a sub-minor-unit difference is settled on the last leg`() {
        val legs = SaleRepository.settleExactly(listOf(SaleRepository.Tender("card", 104.39)), 104.391, 0.01)
        assertEquals(104.391, legs.single().amount, 0.0000001)
    }

    @Test
    fun `non-cash overpay is refused, never trimmed silently`() {
        assertThrows(IllegalArgumentException::class.java) {
            SaleRepository.settleExactly(listOf(SaleRepository.Tender("card", 120.0)), 104.391, 0.01)
        }
    }
}
