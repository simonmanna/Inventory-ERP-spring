package com.poscafe.pos.data.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.round

/**
 * Cart pricing math — mirrors the server's line folding so an offline receipt
 * matches what the server computes on replay:
 *   unit = (variant price | base price) + Σ accompaniment upcharges + Σ modifier deltas
 *   line = unit × qty − line discount − its share of the transaction discount
 *   tax  = the line's tax row: rounded to the currency scale ([taxScale]);
 *          an inclusive tax is backed out of the price (net = amount − tax)
 *   total = Σ (net + tax), kept at the server's 6 decimal places
 *
 * Phase I6: the total is computed the way the server's `DocumentBuilderService`
 * / `TaxCalculationService` do (decimal maths, tax rounded per line to the
 * currency's decimals), because the device sends it as `expectedTotal` and the
 * server compares it to 0.000001. Parity is enforced by golden test vectors
 * exported from the server — see CartVectorParityTest.
 */
object CartEngine {

    /**
     * Decimal places the server rounds line tax to: the organisation currency's
     * `decimalPlaces` ([com.poscafe.pos.data.DeviceConfig.taxScale]); a
     * negative value means the server has no currency and keeps 6.
     */
    @Volatile var taxScale: Int = 2

    /** decimal.js precision the server divides with (`Money.DECIMAL_JS`). */
    private val JS = MathContext(20, RoundingMode.HALF_UP)
    private val HUNDRED = BigDecimal(100)

    @Serializable data class ModifierSel(val modifierId: String, val name: String, val priceDelta: Double)
    @Serializable data class AccompanimentSel(val optionId: String, val name: String, val priceImpact: Double)

    @Serializable data class CartLine(
        val lineId: String,
        val menuItemId: String? = null,
        val productId: String? = null,
        val name: String,
        val quantity: Double,
        /** Base or variant unit price (MAJOR units), before add-ons. */
        val baseUnitPrice: Double,
        val variantId: String? = null,
        val variantName: String? = null,
        val modifiers: List<ModifierSel> = emptyList(),
        val accompaniments: List<AccompanimentSel> = emptyList(),
        val discountPercent: Double = 0.0,
        val taxRatePercent: Double = 0.0,
        val taxInclusive: Boolean = false,
        val note: String? = null,
        /** The catalog tax the rate came from (menu item / product `taxId`). */
        val taxId: String? = null,
        /** The unit the line is sold in (the product's sales unit); the server POS sells in it. */
        val uomId: String? = null,
        /** Retail: the product variant's name, shown on the line and receipt. */
        val productVariantName: String? = null,
    )

    data class LineTotals(
        val unitPrice: Double,
        val gross: Double,
        val discount: Double,
        /** The line's value after discounts (tax included when the tax is inclusive) — what the receipt shows. */
        val net: Double,
        val tax: Double,
        /** What the line adds to the total: net + exclusive tax, at the server's 6 decimals. */
        val total: Double = net,
    )

    data class CartTotals(
        val subtotal: Double,
        val discountTotal: Double,
        val taxTotal: Double,
        /** Amount due, exactly as the server computes it (6 decimals); sent as `expectedTotal`. */
        val total: Double,
        val lines: Map<String, LineTotals>,
    )

    /** Round to 2dp, half-up — display rounding only. */
    fun round2(v: Double): Double = round(v * 100.0) / 100.0

    /** A JS number as the server reads it (`PosSql.decOf`: shortest decimal repr). */
    private fun dec(v: Double): BigDecimal = BigDecimal.valueOf(v)

    private class Exact(val unit: Double, val gross: BigDecimal, val discount: BigDecimal, val amount: BigDecimal,
                        val tax: BigDecimal, val total: BigDecimal)

    private fun exact(line: CartLine, transactionDiscountPercent: Double, scale: Int): Exact {
        // Server order (PosOrdersService.resolveLines): base + accompaniments + modifiers, as doubles.
        val unit = line.baseUnitPrice + line.accompaniments.sumOf { it.priceImpact } + line.modifiers.sumOf { it.priceDelta }
        val gross = dec(line.quantity).multiply(dec(unit))
        // PricingPolicy.discountedLines: line discount + the line's share of the order discount, sent on as a double.
        val lineDiscount = gross.multiply(dec(line.discountPercent)).divide(HUNDRED, JS)
        val extra = gross.subtract(lineDiscount).multiply(dec(transactionDiscountPercent)).divide(HUNDRED, JS)
        val discountRaw = dec(lineDiscount.add(extra).toDouble())
        val discount = if (discountRaw.signum() > 0) discountRaw.min(gross) else BigDecimal.ZERO
        val amount = gross.subtract(discount)
        val rate = dec(line.taxRatePercent)
        val dp = if (scale < 0) 6 else scale
        val amount6 = amount.setScale(6, RoundingMode.HALF_UP)
        val tax: BigDecimal
        val net: BigDecimal
        if (line.taxRatePercent <= 0.0) {
            tax = BigDecimal.ZERO
            net = amount6
        } else if (line.taxInclusive) {
            val netRaw = amount.divide(BigDecimal.ONE.add(rate.divide(HUNDRED, JS)), JS)
            tax = netRaw.multiply(rate.divide(HUNDRED, JS)).setScale(dp, RoundingMode.HALF_UP)
            net = if (scale < 0) netRaw.setScale(6, RoundingMode.HALF_UP) else amount6.subtract(tax)
        } else {
            tax = amount.multiply(rate.divide(HUNDRED, JS)).setScale(dp, RoundingMode.HALF_UP)
            net = amount6
        }
        return Exact(unit, gross, discount, amount, tax, net.add(tax))
    }

    fun lineTotals(line: CartLine, transactionDiscountPercent: Double = 0.0, scale: Int = taxScale): LineTotals {
        val e = exact(line, transactionDiscountPercent, scale)
        return LineTotals(
            unitPrice = round2(e.unit),
            gross = round2(e.gross.toDouble()),
            discount = round2(e.discount.toDouble()),
            net = round2(e.amount.toDouble()),
            tax = e.tax.toDouble(),
            total = e.total.toDouble(),
        )
    }

    fun totals(lines: List<CartLine>, transactionDiscountPercent: Double = 0.0, scale: Int = taxScale): CartTotals {
        var gross = BigDecimal.ZERO
        var discount = BigDecimal.ZERO
        var tax = BigDecimal.ZERO
        var total = BigDecimal.ZERO
        val perLine = mutableMapOf<String, LineTotals>()
        for (line in lines) {
            val e = exact(line, transactionDiscountPercent, scale)
            perLine[line.lineId] = LineTotals(
                unitPrice = round2(e.unit),
                gross = round2(e.gross.toDouble()),
                discount = round2(e.discount.toDouble()),
                net = round2(e.amount.toDouble()),
                tax = e.tax.toDouble(),
                total = e.total.toDouble(),
            )
            gross = gross.add(e.gross)
            discount = discount.add(e.discount)
            tax = tax.add(e.tax)
            total = total.add(e.total)
        }
        return CartTotals(
            subtotal = round2(gross.toDouble()),
            discountTotal = round2(discount.toDouble()),
            taxTotal = tax.setScale(6, RoundingMode.HALF_UP).toDouble(),
            total = total.max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP).toDouble(),
            lines = perLine,
        )
    }

    /**
     * `MenuItem.basePrice` is MAJOR units on the server, like every other
     * price: the web terminal and `pos.checkout` use it as the unit price
     * as is, and the seed stores e.g. 7000 for a UGX 7,000 eclair.
     * Builds before catalog-units v1 assumed MINOR (÷100 on pull, ×100 on
     * push), so a `menuItem.upsert` still sitting in the queue from such a
     * build carries a ×100 basePrice. This rescales one payload; any other
     * payload is returned unchanged.
     */
    fun rescaleLegacyMenuItemPayload(payloadJson: String): String {
        val obj = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return payloadJson
        val price = (obj["basePrice"] as? JsonPrimitive)?.doubleOrNull ?: return payloadJson
        return JsonObject(obj + ("basePrice" to JsonPrimitive(price / 100.0))).toString()
    }
}
