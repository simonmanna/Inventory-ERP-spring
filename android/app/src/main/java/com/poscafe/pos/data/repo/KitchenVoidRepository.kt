package com.poscafe.pos.data.repo

import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.dao.CashSessionDao
import com.poscafe.pos.data.local.dao.InventoryDao
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.entity.InventoryMovementEntity
import com.poscafe.pos.data.local.entity.OpQueueEntity
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voiding food that was already sent to the kitchen from a device-held tab
 * (Phase I6). The tab never reached the server, so without this the started
 * food would vanish from the books. The void needs a reason and a manager
 * approval (PIN verified on-device; the PIN travels with the op and the server
 * re-verifies it for the `void` override kind). The server wastes the menu
 * item's recipe components / the product at the shift's register location,
 * once per op (`kitchen.void`). Product lines also leave the device's own
 * movement stock, tagged with the op so a final rejection undoes them.
 */
@Singleton
class KitchenVoidRepository @Inject constructor(
    private val opQueue: OpQueueDao,
    private val cashSessionDao: CashSessionDao,
    private val inventoryDao: InventoryDao,
    private val config: DeviceConfig,
) {
    /** One voided line: the quantity taken off a fired cart line. */
    data class Voided(val line: CartEngine.CartLine, val quantity: Double)

    suspend fun record(
        actorUserId: String,
        tableLabel: String,
        reason: String,
        overrideById: String,
        overridePin: String,
        voided: List<Voided>,
    ): String {
        require(reason.isNotBlank()) { "A reason is required to void an item" }
        val lines = voided.filter { it.quantity > 0.0 && (it.line.menuItemId != null || it.line.productId != null) }
        val opId = UUID.randomUUID().toString()
        if (lines.isEmpty()) return opId
        val now = System.currentTimeMillis()

        runCatching {
            val movements = lines.filter { it.line.productId != null }.map { v ->
                InventoryMovementEntity(
                    id = UUID.randomUUID().toString(),
                    menuItemId = v.line.menuItemId ?: "",
                    productId = v.line.productId,
                    type = "waste",
                    qtyDelta = -v.quantity,
                    unitCost = null,
                    supplierId = null,
                    reason = "Kitchen void: ${reason.trim()}",
                    saleLocalId = null,
                    actorUserId = actorUserId,
                    occurredAt = now,
                    variantId = v.line.variantId,
                    opId = opId,
                )
            }
            if (movements.isNotEmpty()) inventoryDao.insertAll(movements)
        }

        // Nothing to tell a standalone till's (non-existent) server.
        if (config.standalone) return opId

        val session = cashSessionDao.open()
        opQueue.enqueueNext { seq ->
            OpQueueEntity(
                opId = opId,
                deviceSeq = seq,
                type = "kitchen.void",
                actorUserId = actorUserId,
                occurredAt = now,
                payloadJson = buildJsonObject {
                    session?.let { put("cashSessionId", it.serverId ?: it.id) }
                    put("tableLabel", tableLabel)
                    put("reason", reason.trim())
                    put("overrideById", overrideById)
                    put("overridePin", overridePin)
                    put(
                        "lines",
                        buildJsonArray {
                            lines.forEach { v ->
                                add(
                                    buildJsonObject {
                                        v.line.menuItemId?.let { put("menuItemId", it) }
                                        v.line.productId?.let { put("productId", it) }
                                        v.line.variantId?.let { put("variantId", it) }
                                        put("quantity", v.quantity)
                                        put("description", v.line.name)
                                    },
                                )
                            }
                        },
                    )
                }.toString(),
                status = "queued",
                attempts = 0,
                lastError = null,
            )
        }
        return opId
    }
}
