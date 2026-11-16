package com.poscafe.pos.data.repo

import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.dao.InventoryDao
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.dao.SaleDao
import com.poscafe.pos.data.local.entity.InventoryMovementEntity
import com.poscafe.pos.data.local.entity.LocalSaleEntity
import com.poscafe.pos.data.local.entity.OpQueueEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline checkout. Every sale is:
 *   1. Priced locally (CartEngine — parity with the server via golden vectors),
 *   2. Persisted as a LocalSaleEntity (the device's own record; device wins),
 *   3. Enqueued as a `sale.checkout` op whose payload is exactly the online
 *      CheckoutDto body (+ occurredAt, provisionalNumber, clientId refs),
 *   4. Given a provisional receipt number (D<n>-NNNNNN) for the printed ticket.
 *
 * The sale NEVER blocks on the network — the queue pushes when connectivity
 * returns and the server allocates the final invoice number then.
 */
@Singleton
class SaleRepository @Inject constructor(
    private val saleDao: SaleDao,
    private val opQueue: OpQueueDao,
    private val inventoryDao: InventoryDao,
    private val config: DeviceConfig,
) {
    /**
     * One payment leg. [accountId] is the receiving account of the configured
     * payment tile (null = cash drawer / the server's default mapping).
     */
    data class Tender(val method: String, val amount: Double, val reference: String? = null, val accountId: String? = null)

    data class CompletedSale(
        val localId: String,
        val provisionalNumber: String,
        val totals: CartEngine.CartTotals,
        val occurredAt: Instant,
        /** Cash to hand back on an overpay; 0 when the legs paid exactly. */
        val change: Double = 0.0,
    )

    private val json = Json { encodeDefaults = false }

    suspend fun checkout(
        actorUserId: String,
        lines: List<CartEngine.CartLine>,
        tenders: List<Tender>,
        cashSessionLocalId: String?,
        tableId: String? = null,
        orderType: String = "takeaway",
        transactionDiscountPercent: Double = 0.0,
        partnerId: String? = null,
    ): CompletedSale {
        require(lines.isNotEmpty()) { "Cart is empty" }
        CartEngine.taxScale = config.taxScale
        val totals = CartEngine.totals(lines, transactionDiscountPercent)
        val tenderSum = tenders.sumOf { it.amount }
        val minor = minorUnit()
        require(tenderSum >= totals.total - minor) { "Tendered $tenderSum < due ${totals.total}" }
        require(tenders.none { it.method == "cash" } || cashSessionLocalId != null) {
            "Open a cash session before taking cash"
        }
        // The server wants the legs to settle exactly the amount due; change is
        // carried separately as `amountTendered`. Cash overpay becomes change
        // (taken off the cash legs, last first); a difference under one minor
        // currency unit (the exact total has 6 decimals, money is typed in whole
        // units) is absorbed by the last leg. Anything else is refused here —
        // never trimmed silently.
        val applied = settleExactly(tenders, totals.total, minor)
        val cashHanded = if (tenderSum > totals.total + minor && tenders.any { it.method == "cash" }) tenderSum else null

        val localId = UUID.randomUUID().toString()
        val occurredAt = Instant.now()
        val provisionalNumber = config.nextProvisionalNumber()

        val payload = buildJsonObject {
            put("lines", linesToJson(lines))
            put("tenders", buildJsonArray {
                applied.forEach { t ->
                    add(buildJsonObject {
                        put("method", t.method)
                        put("amount", t.amount)
                        t.reference?.let { put("reference", it) }
                        t.accountId?.let { put("accountId", it) }
                    })
                }
            })
            cashHanded?.let { put("amountTendered", it) }
            // Phase I6: the total the device charged. The server re-prices from its catalog; when it disagrees
            // the sale is still kept (owner decision 1) and the difference is flagged on the invoice.
            put("expectedTotal", totals.total)
            if (transactionDiscountPercent > 0) put("transactionDiscountPercent", transactionDiscountPercent)
            // clientId of the locally-opened session — the server resolves it
            // to the real session id created earlier in the same push batch.
            cashSessionLocalId?.let { put("cashSessionId", it) }
            tableId?.let { put("tableId", it) }
            partnerId?.let { put("partnerId", it) }
            put("orderType", orderType)
            put("provisionalNumber", provisionalNumber)
            put("clientId", localId)
        }

        saleDao.insert(
            LocalSaleEntity(
                id = localId,
                occurredAt = occurredAt.toEpochMilli(),
                actorUserId = actorUserId,
                cashSessionLocalId = cashSessionLocalId,
                tableId = tableId,
                orderType = orderType,
                provisionalNumber = provisionalNumber,
                subtotal = totals.subtotal,
                taxAmount = totals.taxTotal,
                total = totals.total,
                tendersJson = json.encodeToString(JsonArray.serializer(), payload["tenders"] as JsonArray),
                linesJson = json.encodeToString(JsonArray.serializer(), payload["lines"] as JsonArray),
                syncStatus = "queued",
                serverInvoiceId = null,
                finalInvoiceNumber = null,
                lastError = null,
            ),
        )

        // Movement-based stock: one 'sale' issue per catalog/product line.
        // Best-effort and never blocks the sale; negative on-hand is allowed.
        runCatching {
            inventoryDao.insertAll(
                lines.filter { it.menuItemId != null || it.productId != null }.map { l ->
                    InventoryMovementEntity(
                        id = UUID.randomUUID().toString(),
                        menuItemId = l.menuItemId ?: "",
                        productId = l.productId,
                        // A retail line's variant is a product variant (its own quant on the server).
                        variantId = if (l.productId != null) l.variantId else null,
                        type = "sale",
                        qtyDelta = -l.quantity,
                        unitCost = null,
                        supplierId = null,
                        reason = null,
                        saleLocalId = localId,
                        actorUserId = actorUserId,
                        occurredAt = occurredAt.toEpochMilli(),
                    )
                },
            )
        }

        opQueue.enqueueNext { seq ->
            OpQueueEntity(
                opId = localId,
                deviceSeq = seq,
                type = "sale.checkout",
                actorUserId = actorUserId,
                occurredAt = occurredAt.toEpochMilli(),
                payloadJson = payload.toString(),
                status = "queued",
                attempts = 0,
                lastError = null,
            )
        }

        return CompletedSale(localId, provisionalNumber, totals, occurredAt, change = cashHanded?.let { it - totals.total } ?: 0.0)
    }

    /** One minor unit of the server's currency (tax rounding scale); 0.000001 when it keeps 6 places. */
    private fun minorUnit(): Double = config.taxScale.let { s -> if (s < 0) 0.000001 else Math.pow(10.0, -s.toDouble()) }

    companion object {
    /**
     * Legs that pay exactly [total]: a cash overpay is change and comes off the
     * cash legs (last first); a rounding difference under [minor] goes on the
     * last leg. Legs reduced to nothing are dropped (their cash is in the change).
     */
    internal fun settleExactly(tenders: List<Tender>, total: Double, minor: Double): List<Tender> {
        val exact = java.math.BigDecimal.valueOf(total)
        fun sum(ts: List<Tender>) = ts.fold(java.math.BigDecimal.ZERO) { a, t -> a.add(java.math.BigDecimal.valueOf(t.amount)) }
        val out = tenders.toMutableList()
        var over = sum(out).subtract(exact)
        val tolerance = java.math.BigDecimal.valueOf(minor)
        if (over > tolerance) {
            require(out.any { it.method == "cash" }) { "Only cash can be overpaid — reduce the non-cash amount" }
            for (i in out.indices.reversed()) {
                if (over.signum() <= 0) break
                val t = out[i]
                if (t.method != "cash") continue
                val amount = java.math.BigDecimal.valueOf(t.amount)
                val cut = amount.min(over)
                out[i] = t.copy(amount = amount.subtract(cut).toDouble())
                over = over.subtract(cut)
            }
            require(over <= tolerance) { "Only cash can be overpaid — reduce the non-cash amount" }
        }
        val kept = out.filter { it.amount > 0.0 }.toMutableList()
        // Rounding residue (both signs, under one minor unit): the last leg settles it.
        val residue = sum(kept).subtract(exact)
        if (residue.signum() != 0 && kept.isNotEmpty()) {
            val last = kept.last()
            kept[kept.lastIndex] = last.copy(amount = java.math.BigDecimal.valueOf(last.amount).subtract(residue).toDouble())
        }
        return kept
    }
    }

    private fun linesToJson(lines: List<CartEngine.CartLine>): JsonArray = buildJsonArray {
        lines.forEach { l ->
            add(buildJsonObject {
                l.menuItemId?.let { put("menuItemId", it) }
                l.productId?.let { put("productId", it) }
                put("description", l.name)
                put("quantity", l.quantity)
                // The server re-resolves modifier/accompaniment prices from its
                // own DB (anti-tamper), so we send the UN-baked base price and
                // the selections — never the folded unit price.
                put("unitPrice", l.baseUnitPrice)
                if (l.discountPercent > 0) put("discountPercent", l.discountPercent)
                // A menu line's variant is a MenuItemVariant; a product line's a ProductVariant (ADR-S058).
                l.variantId?.let { put("variantId", it) }
                l.productVariantName?.let { put("variantName", it) }
                // The server prices from its catalog and sells in the product's sales unit; the device states
                // what it rang up so the op keeps a record of it (owner decision 1).
                l.uomId?.let { put("uomId", it) }
                l.taxId?.let { put("taxId", it) }
                if (l.accompaniments.isNotEmpty()) {
                    put("accompanimentOptionIds", buildJsonArray {
                        l.accompaniments.forEach { add(JsonPrimitive(it.optionId)) }
                    })
                }
                if (l.modifiers.isNotEmpty()) {
                    put("modifiers", buildJsonArray {
                        l.modifiers.forEach { m ->
                            add(buildJsonObject {
                                put("modifierId", m.modifierId)
                                put("name", m.name)
                                put("priceDelta", m.priceDelta)
                            })
                        }
                    })
                }
                l.note?.let { put("note", it) }
                if (l.taxInclusive) put("taxInclusive", true)
            })
        }
    }
}
