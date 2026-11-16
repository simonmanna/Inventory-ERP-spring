package com.poscafe.pos.data.repo

import com.poscafe.pos.data.local.dao.CashSessionDao
import com.poscafe.pos.data.local.dao.InventoryDao
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.dao.RefundDao
import com.poscafe.pos.data.local.dao.SaleDao
import com.poscafe.pos.data.local.entity.InventoryMovementEntity
import com.poscafe.pos.data.local.entity.LocalRefundEntity
import com.poscafe.pos.data.local.entity.OpQueueEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline refunds & voids. Mirrors [SaleRepository]:
 *   1. Write the LocalRefundEntity (device wins for its own money records),
 *   2. Put restocked goods back in the movement-based stock (best-effort, never blocks),
 *   3. Enqueue a `sale.refund` / `sale.void` op whose payload is the online
 *      RefundDto body. The server reverses the invoice GL, handles the stock
 *      per the disposition, and returns drawer cash when the op replays.
 *
 * A void is a full refund with a MANDATORY manager override — the manager PIN
 * is verified on-device (bcrypt, like cashier login) before we ever enqueue.
 *
 * Partial refunds (Phase I6): lines are addressed by their position in the
 * sale (`lineNumber` on the wire = index + 1; the server numbers invoice lines
 * in checkout order). Each refund carries one stock disposition — restock,
 * waste or no_return — so a refund with mixed dispositions is one op per
 * disposition. Only restocked goods come back to the device's stock.
 */
@Singleton
class RefundRepository @Inject constructor(
    private val refundDao: RefundDao,
    private val saleDao: SaleDao,
    private val cashSessionDao: CashSessionDao,
    private val opQueue: OpQueueDao,
    private val inventoryDao: InventoryDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** One refunded line: [lineIndex] is the 0-based position in the sale's lines. */
    data class RefundLine(val lineIndex: Int, val quantity: Double)

    /** A sale line with what is still refundable on it. */
    data class Refundable(val lineIndex: Int, val description: String, val sold: Double, val remaining: Double)

    /**
     * Refund of a settled sale. [lines] = null refunds everything still
     * refundable. [overrideById] is the manager who approved it (PIN-verified
     * on-device). The server demands a transaction-bound approval, so the
     * manager's [overridePin] travels with the op and is re-verified on replay
     * (the queue lives in the SQLCipher DB).
     */
    suspend fun refund(
        actorUserId: String,
        saleLocalId: String,
        reason: String,
        overrideById: String,
        overridePin: String,
        lines: List<RefundLine>? = null,
        stockDisposition: String = "restock",
    ): LocalRefundEntity =
        record(actorUserId, saleLocalId, "refund", reason, overrideById, overridePin, lines, stockDisposition)

    /**
     * Void = full refund with a mandatory manager override. [overrideById] is
     * the manager whose PIN was verified on-device.
     */
    suspend fun void(
        actorUserId: String,
        saleLocalId: String,
        reason: String,
        overrideById: String,
        overridePin: String,
        stockDisposition: String = "restock",
    ): LocalRefundEntity =
        record(actorUserId, saleLocalId, "void", reason, overrideById, overridePin, null, stockDisposition)

    /** What is still refundable on [saleLocalId], per sale line. */
    suspend fun refundable(saleLocalId: String): List<Refundable> {
        val sale = saleDao.byId(saleLocalId) ?: return emptyList()
        val saleLines = saleLines(sale.linesJson)
        val refunded = refundedQty(saleLocalId, saleLines)
        return saleLines.mapIndexed { i, l ->
            Refundable(i, l.description, l.quantity, (l.quantity - (refunded[i] ?: 0.0)).coerceAtLeast(0.0))
        }
    }

    private class SaleLine(
        val menuItemId: String?,
        val productId: String?,
        val variantId: String?,
        val description: String,
        val quantity: Double,
        val value: Double,
    )

    private fun saleLines(linesJson: String): List<SaleLine> {
        val arr = json.parseToJsonElement(linesJson) as? JsonArray ?: return emptyList()
        return arr.filterIsInstance<JsonObject>().map { l ->
            fun str(k: String) = l[k]?.jsonPrimitive?.contentOrNull
            fun num(k: String) = str(k)?.toDoubleOrNull()
            val qty = num("quantity") ?: 0.0
            val modifiers = (l["modifiers"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.sumOf { it["priceDelta"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0 } ?: 0.0
            val unit = (num("unitPrice") ?: 0.0) + modifiers
            SaleLine(
                menuItemId = str("menuItemId"),
                productId = str("productId"),
                variantId = str("variantId"),
                description = str("description") ?: "Item",
                quantity = qty,
                value = unit * qty * (1 - (num("discountPercent") ?: 0.0) / 100.0),
            )
        }
    }

    /** Quantity already refunded per line index (a refund without lines took everything). */
    private suspend fun refundedQty(saleLocalId: String, saleLines: List<SaleLine>): Map<Int, Double> {
        val out = mutableMapOf<Int, Double>()
        for (r in refundDao.forSale(saleLocalId)) {
            if (r.syncStatus == "discarded") continue
            val rl = r.linesJson?.let { decodeLines(it) }
            if (rl == null) {
                saleLines.forEachIndexed { i, l -> out[i] = l.quantity }
            } else {
                rl.forEach { out[it.lineIndex] = (out[it.lineIndex] ?: 0.0) + it.quantity }
            }
        }
        return out
    }

    private fun decodeLines(s: String): List<RefundLine>? = runCatching {
        (json.parseToJsonElement(s) as JsonArray).filterIsInstance<JsonObject>().map {
            RefundLine(
                it["lineIndex"]!!.jsonPrimitive.content.toInt(),
                it["quantity"]!!.jsonPrimitive.content.toDouble(),
            )
        }
    }.getOrNull()

    private suspend fun record(
        actorUserId: String,
        saleLocalId: String,
        type: String,
        reason: String,
        overrideById: String,
        overridePin: String,
        requested: List<RefundLine>?,
        stockDisposition: String,
    ): LocalRefundEntity {
        require(reason.isNotBlank()) { "Enter a reason for the ${if (type == "void") "void" else "refund"}" }
        require(stockDisposition in DISPOSITIONS) { "Unknown stock disposition" }
        val sale = saleDao.byId(saleLocalId) ?: error("Sale not found")
        val saleLines = saleLines(sale.linesJson)
        val previous = refundDao.forSale(saleLocalId).filter { it.syncStatus != "discarded" }
        if (type == "void") require(previous.isEmpty()) { "Sale already refunded" }
        val already = refundedQty(saleLocalId, saleLines)

        // Resolve the lines: everything still refundable, or the requested quantities within it.
        val lines: List<RefundLine> = if (requested == null) {
            saleLines.mapIndexedNotNull { i, l ->
                val left = l.quantity - (already[i] ?: 0.0)
                if (left > EPS) RefundLine(i, left) else null
            }
        } else {
            requested.filter { it.quantity > EPS }.groupBy { it.lineIndex }.map { (i, rs) ->
                require(i in saleLines.indices) { "Refund line is not on this sale" }
                val qty = rs.sumOf { it.quantity }
                val left = saleLines[i].quantity - (already[i] ?: 0.0)
                require(qty <= left + EPS) { "Only ${fmt(left)} of ${saleLines[i].description} can still be refunded" }
                RefundLine(i, qty)
            }
        }
        require(lines.isNotEmpty()) { if (previous.isEmpty()) "Select something to refund" else "Sale already refunded" }
        // The whole sale in one go is a plain full refund (no line list on the wire).
        val whole = previous.isEmpty() && saleLines.indices.all { i ->
            val l = lines.firstOrNull { it.lineIndex == i }
            l != null && l.quantity >= saleLines[i].quantity - EPS
        }

        // Local drawer figure: the sale total split by line value. The server computes the real refund.
        val saleValue = saleLines.sumOf { it.value }
        val amount = when {
            whole -> sale.total
            saleValue <= 0.0 -> 0.0
            else -> CartEngine.round2(lines.sumOf { r ->
                val l = saleLines[r.lineIndex]
                if (l.quantity <= 0.0) 0.0 else sale.total * (l.value / saleValue) * (r.quantity / l.quantity)
            })
        }

        val session = cashSessionDao.open()
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        // Real server invoice id when the sale already synced; otherwise the
        // sale's local id — the server resolves it via the batch clientId map.
        val invoiceRef = sale.serverInvoiceId ?: sale.id
        // Prefer the current open session's server id so the refund cash-out
        // lands on the drawer that is physically open; resolved server-side if
        // the session was opened earlier in the same push batch.
        val cashSessionRef = session?.let { it.serverId ?: it.id }

        val row = LocalRefundEntity(
            id = id,
            saleLocalId = saleLocalId,
            serverInvoiceId = invoiceRef,
            type = type,
            reason = reason,
            amount = amount,
            overrideById = overrideById,
            cashSessionLocalId = session?.id,
            occurredAt = now.toEpochMilli(),
            syncStatus = "queued",
            lastError = null,
            linesJson = if (whole) {
                null
            } else {
                buildJsonArray {
                    lines.forEach { l ->
                        add(buildJsonObject { put("lineIndex", l.lineIndex); put("quantity", l.quantity) })
                    }
                }.toString()
            },
            stockDisposition = stockDisposition,
        )
        refundDao.insert(row)

        // Only restocked goods come back to the shelf; waste / no_return leave the device's stock alone
        // (the server books the waste itself).
        if (stockDisposition == "restock") {
            runCatching {
                val movements = lines.mapNotNull { r ->
                    val l = saleLines[r.lineIndex]
                    if (l.menuItemId == null && l.productId == null) return@mapNotNull null
                    InventoryMovementEntity(
                        id = UUID.randomUUID().toString(),
                        menuItemId = l.menuItemId ?: "",
                        productId = l.productId,
                        type = "refund",
                        qtyDelta = r.quantity, // add back
                        unitCost = null,
                        supplierId = null,
                        reason = "Refund ${sale.provisionalNumber}",
                        saleLocalId = saleLocalId,
                        actorUserId = actorUserId,
                        occurredAt = now.toEpochMilli(),
                        variantId = if (l.productId != null) l.variantId else null,
                        opId = id,
                    )
                }
                if (movements.isNotEmpty()) inventoryDao.insertAll(movements)
            }
        }

        val opType = if (type == "void") "sale.void" else "sale.refund"
        opQueue.enqueueNext { seq ->
            OpQueueEntity(
                opId = id,
                deviceSeq = seq,
                type = opType,
                actorUserId = actorUserId,
                occurredAt = now.toEpochMilli(),
                payloadJson = buildJsonObject {
                    put("invoiceId", invoiceRef)
                    put("reason", reason.trim())
                    cashSessionRef?.let { put("cashSessionId", it) }
                    put("overrideById", overrideById)
                    put("overridePin", overridePin)
                    put("stockDisposition", stockDisposition)
                    if (!whole) {
                        put(
                            "lines",
                            buildJsonArray {
                                lines.forEach { l ->
                                    add(
                                        buildJsonObject {
                                            put("lineNumber", l.lineIndex + 1)
                                            put("quantity", l.quantity)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }.toString(),
                status = "queued",
                attempts = 0,
                lastError = null,
            )
        }
        return row
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    companion object {
        val DISPOSITIONS = setOf("restock", "waste", "no_return")
        private const val EPS = 0.000001
    }
}
