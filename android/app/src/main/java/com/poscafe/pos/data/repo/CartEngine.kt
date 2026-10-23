package com.poscafe.pos.data.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.max
import kotlin.math.round

/**
 * Cart pricing math — mirrors the server's line folding so an offline receipt
 * matches what the server computes on replay:
 *   unit = (variant price | base price) + Σ modifier deltas + Σ accompaniment upcharges
 *   line = unit × qty − line discount
 *   tax  = per-line rate; taxInclusive lines back the tax out of the price
 *   total = Σ lines − transaction discount, then tax
 *
 * Parity is enforced by golden test vectors exported from the server
 * (scripts/export-cart-vectors.ts) — see CartEngineTest.
 */
object CartEngine {

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
    )

    data class LineTotals(
        val unitPrice: Double,
        val gross: Double,
        val discount: Double,
        val net: Double,
        val tax: Double,
    )

    data class CartTotals(
        val subtotal: Double,
        val discountTotal: Double,
        val taxTotal: Double,
        val total: Double,
        val lines: Map<String, LineTotals>,
    )

    /** Round to 2dp, half-up — matches the server's money rounding. */
    fun round2(v: Double): Double = round(v * 100.0) / 100.0

    fun lineTotals(line: CartLine, transactionDiscountPercent: Double = 0.0): LineTotals {
        val unit = line.baseUnitPrice +
            line.modifiers.sumOf { it.priceDelta } +
            line.accompaniments.sumOf { it.priceImpact }
        val gross = unit * line.quantity
        val lineDiscount = gross * (line.discountPercent / 100.0)
        val afterLine = gross - lineDiscount
        val txDiscount = afterLine * (transactionDiscountPercent / 100.0)
        val net = afterLine - txDiscount
        val tax = if (line.taxRatePercent <= 0.0) 0.0
        else if (line.taxInclusive) net - (net / (1 + line.taxRatePercent / 100.0))
        else net * (line.taxRatePercent / 100.0)
        return LineTotals(
            unitPrice = round2(unit),
            gross = round2(gross),
            discount = round2(lineDiscount + txDiscount),
            net = round2(net),
            tax = round2(tax),
        )
    }

    fun totals(lines: List<CartLine>, transactionDiscountPercent: Double = 0.0): CartTotals {
        var subtotal = 0.0
        var discountTotal = 0.0
        var taxTotal = 0.0
        var total = 0.0
        val perLine = mutableMapOf<String, LineTotals>()
        for (line in lines) {
            val t = lineTotals(line, transactionDiscountPercent)
            perLine[line.lineId] = t
            subtotal += t.gross
            discountTotal += t.discount
            // Inclusive lines already carry their tax inside net.
            taxTotal += t.tax
            total += if (line.taxInclusive) t.net else t.net + t.tax
        }
        return CartTotals(
            subtotal = round2(subtotal),
            discountTotal = round2(discountTotal),
            taxTotal = round2(taxTotal),
            total = round2(max(0.0, total)),
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
