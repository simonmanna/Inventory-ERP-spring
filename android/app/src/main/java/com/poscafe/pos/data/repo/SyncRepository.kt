package com.poscafe.pos.data.repo

import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.ServerUrlPolicy
import com.poscafe.pos.data.local.PosDatabase
import com.poscafe.pos.data.local.entity.*
import com.poscafe.pos.data.remote.PushOp
import com.poscafe.pos.data.remote.PushRequest
import com.poscafe.pos.data.remote.SyncApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import retrofit2.HttpException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device end of the sync protocol.
 *
 * PULL: incremental catalog download (per-scope updatedAt cursors, opaque to
 * us). Rows with deletedAt are tombstones → local delete. MenuItem arrives as
 * a full aggregate: children are wiped + re-inserted per item.
 *
 * PUSH: the op queue drains FIFO in one batch. Results:
 *   applied/replayed → mark pushed, store invoice mapping + final numbers
 *   failed           → mark failed locally (visible in sync screen); the
 *                      server has dead-lettered it — never re-pushed blindly.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val api: SyncApi,
    private val db: PosDatabase,
    private val config: DeviceConfig,
) {
    /**
     * Serialises every push/pull. The periodic and one-shot workers have
     * different unique names, and screens (setup, messaging) call in directly,
     * so without this two runs could push the same batch and race on the
     * local status updates.
     */
    private val lock = Mutex()

    /**
     * Runs [block] under [lock]. A 401 from the device-token routes on a
     * device that has synced before means the token was revoked in the back
     * office: see [onRevoked].
     */
    private suspend fun <T> guarded(block: suspend () -> T): T = lock.withLock {
        try {
            block()
        } catch (e: HttpException) {
            if (e.code() == 401 && config.isEnrolled && db.syncStateDao().get()?.lastPullAt != null) {
                onRevoked()
                throw DeviceRevokedException()
            }
            throw e
        }
    }

    /**
     * Revocation wipe. Staff rows (PIN hashes) always go, so nobody can sign
     * in again; enrollment is forgotten so the app falls back to setup. The
     * rest of the database is cleared only when nothing is left to sync —
     * unsynced sales stay encrypted on the device and drain once a manager
     * re-enrolls it, because money is never silently dropped.
     */
    private suspend fun onRevoked() {
        db.staffDao().deleteAll()
        val unsynced = db.opQueueDao().queuedNow() + db.opQueueDao().failedNow()
        if (unsynced == 0) withContext(Dispatchers.IO) { db.clearAllTables() }
        config.clearEnrollment()
        config.markRevoked(true)
    }

    /**
     * One-time repair for devices upgraded from builds that stored menu
     * basePrice ÷100 (see [CartEngine.rescaleLegacyMenuItemPayload]):
     *  1. queued menuItem.upsert ops from the old build carry a ×100 price —
     *     rescale them before they are pushed (runs once, own flag);
     *  2. every pulled menu item in Room is ÷100 — [pull] ignores the cursor
     *     until one full snapshot has re-applied the catalog at true prices.
     * Sales already queued keep the price they were rung up at: that is what
     * the customer paid, so they are never rewritten.
     */
    private suspend fun ensureCatalogUnits() {
        if (config.legacyMenuQueueRescaled) return
        val dao = db.opQueueDao()
        for (op in dao.unpushedOfTypeBefore("menuItem.upsert", config.appUpdatedAt)) {
            dao.updatePayload(op.opId, CartEngine.rescaleLegacyMenuItemPayload(op.payloadJson))
        }
        config.legacyMenuQueueRescaled = true
    }

    suspend fun pull() = guarded { pullLocked() }

    /**
     * Point the device at a new server address (new router IP, moved to
     * HTTPS, ...). The address is saved only if that server accepts this
     * device's token; a 401 here is reported, not treated as revocation, so
     * a typo can never wipe the till.
     * @return null on success, otherwise why the address was not saved.
     */
    suspend fun changeServer(url: String): String? = lock.withLock {
        val normalized = url.trim().removeSuffix("/") + "/"
        ServerUrlPolicy.problem(normalized)?.let { return@withLock it }
        val previous = config.serverUrl
        config.serverUrl = normalized
        try {
            api.pull(cursor = null, scopes = "settings")
            null
        } catch (e: Exception) {
            config.serverUrl = previous
            if (e is HttpException && e.code() == 401) {
                "That server does not recognise this device. Re-enroll it with a new device id and token."
            } else {
                "Could not reach the server: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * Manager-initiated re-enrolment: forget the token but keep every local
     * row, so unsynced sales push once the new enrolment's first sync runs.
     */
    suspend fun startReenroll() = lock.withLock {
        config.clearEnrollment()
        config.requestSetup()
    }

    /**
     * Requeues rejected ops ([opId] null = all). The server locks an op's
     * idempotency key after any failure, so resending with the same key only
     * ever gets 409 "needs recovery". When the first rejection was definitive
     * (4xx — the server says nothing was committed and a new key is safe) the
     * op goes out under a fresh key; anything else keeps its key, as the
     * server requires for possibly-committed work.
     */
    suspend fun retryRejected(opId: String? = null) = lock.withLock {
        val dao = db.opQueueDao()
        for (op in dao.failedNowList()) {
            if (opId != null && op.opId != opId) continue
            if (op.rejectedStatus != null) dao.requeueWithNewKey(op.opId, UUID.randomUUID().toString())
            else dao.retry(op.opId)
        }
    }

    /**
     * A manager gives up on a rejected op (e.g. its idempotency key is burned
     * server-side, so no retry can ever succeed). The server keeps its own
     * dead-letter row; locally the op and its sale/refund/expense row become
     * 'discarded', drop out of shift totals and stop blocking shift close.
     * Returns false when the op was not (or no longer) rejected.
     */
    suspend fun discardRejected(opId: String, approverName: String): Boolean = lock.withLock {
        val dao = db.opQueueDao()
        val op = dao.unpushed().firstOrNull { it.opId == opId && it.status == "failed" } ?: return@withLock false
        val note = "Discarded by $approverName — ${op.lastError ?: "rejected"}"
        if (dao.discard(opId, note) == 0) return@withLock false
        db.saleDao().markSync(opId, "discarded", null, null, note)
        db.refundDao().markSync(opId, "discarded", null, note)
        db.messageDao().markState(opId, "failed", note)
        db.expenseDao().markSync(opId, "discarded", note)
        db.purchaseDao().markSync(opId, "discarded")
        true
    }

    /**
     * Called by setup before the first pull of a new enrolment. The cursor
     * belongs to whatever server the device talked to before; sent to a new
     * server it would ask only for changes since an unrelated point in time
     * and leave the catalog half-loaded. Dropping it makes the first pull a
     * full snapshot, which also prunes the previous server's catalog and staff.
     */
    suspend fun resetForEnrollment() = lock.withLock {
        val state = db.syncStateDao().get() ?: return@withLock
        db.syncStateDao().put(state.copy(cursor = null))
    }

    private suspend fun pullLocked() {
        ensureCatalogUnits()
        val fullResync = config.catalogUnitsVersion < CATALOG_UNITS_VERSION
        val state = db.syncStateDao().get()
        val cursor = if (fullResync) null else state?.cursor
        val res = api.pull(cursor = cursor)

        res.data["menuCategories"]?.let { applyCategories(it) }
        res.data["taxes"]?.let { applyTaxes(it) }
        res.data["modifierGroups"]?.let { applyModifierGroups(it) }
        res.data["menuItems"]?.let { applyMenuItems(it) }
        res.data["posTables"]?.let { applyTables(it) }
        res.data["cashRegisters"]?.let { applyRegisters(it) }
        res.data["staff"]?.let { applyStaff(it) }
        res.data["settings"]?.let { applySettings(it) }
        res.data["products"]?.let { applyProducts(it) }
        res.data["productCategories"]?.let { applyProductCategories(it) }
        res.data["productPackagings"]?.let { applyProductPackagings(it) }
        res.data["partners"]?.let { applyPartners(it) }
        res.data["reservations"]?.let { applyReservations(it) }
        res.data["conversations"]?.let { applyConversations(it) }
        res.data["messages"]?.let { applyMessages(it) }
        // Cash & inventory reference data (full snapshots, except stock/suppliers).
        res.data["paymentMethods"]?.let { applyPaymentMethods(it) }
        res.data["ledgerAccounts"]?.let { applyLedgerAccounts(it) }
        res.data["expenseCategories"]?.let { applyExpenseCategories(it) }
        res.data["stockLocations"]?.let { applyStockLocations(it) }
        res.data["stockLevels"]?.let { applyStockLevels(it) }
        res.data["suppliers"]?.let { applySuppliers(it) }
        // Without a cursor the server sent everything it has, so anything else is stale.
        if (cursor == null) pruneToSnapshot(res.data)

        db.syncStateDao().put(
            SyncStateEntity(
                cursor = res.cursor,
                lastPullAt = Instant.now().toEpochMilli(),
                lastPushAt = state?.lastPushAt,
            ),
        )
        if (fullResync) config.catalogUnitsVersion = CATALOG_UNITS_VERSION
        // The installation's licence, as the server sees it right now. A pull from an older server
        // carries none, in which case the last known answer stands rather than being reset.
        res.license?.let { licence ->
            config.applyLicense(
                mode = licence.mode,
                writable = licence.writable,
                message = licence.message,
                daysRemaining = licence.daysRemaining,
                graceUntil = licence.graceUntil,
            )
        }
    }

    /**
     * Drain the whole queue, one 100-op batch at a time. A till that sold
     * offline all day can have hundreds of ops; a single [push] would leave
     * the rest waiting for the next periodic run. Stops as soon as a batch
     * makes no progress (server returned fewer results than sent).
     * @return ops rejected across all batches.
     */
    suspend fun pushAll(): Int = guarded {
        var rejected = 0
        var remaining = db.opQueueDao().queuedNow()
        while (remaining > 0) {
            rejected += pushLocked()
            val after = db.opQueueDao().queuedNow()
            if (after >= remaining) break
            remaining = after
        }
        rejected
    }

    /** @return number of ops that remain queued or failed after this push. */
    suspend fun push(): Int = guarded { pushLocked() }

    private suspend fun pushLocked(): Int {
        ensureCatalogUnits()
        val deviceId = config.deviceId ?: error("Device not enrolled")
        val ops = resolveSyncedSessions(db.opQueueDao().nextBatch())
        if (ops.isEmpty()) return 0

        val request = PushRequest(
            deviceId = deviceId,
            ops = ops.map { op ->
                PushOp(
                    opId = op.wireOpId ?: op.opId,
                    deviceSeq = op.deviceSeq,
                    type = op.type,
                    actorUserId = op.actorUserId,
                    occurredAt = Instant.ofEpochMilli(op.occurredAt).toString(),
                    payload = Json.parseToJsonElement(op.payloadJson),
                )
            },
        )
        // Batch id doubles as the HTTP-level idempotency key; per-op keys are
        // the opIds, enforced server-side.
        val response = api.push(batchId = UUID.randomUUID().toString(), body = request)

        // Results name ops by the key that went over the wire.
        val localIds = ops.associate { (it.wireOpId ?: it.opId) to it.opId }
        var unresolved = 0
        for (wireResult in response.results) {
            val result = wireResult.copy(opId = localIds[wireResult.opId] ?: wireResult.opId)
            when (result.status) {
                "applied", "replayed" -> {
                    db.opQueueDao().mark(result.opId, "pushed", null)
                    applyMapping(result.opId, result.mapping, result.finalNumbers)
                }
                else -> {
                    unresolved += 1
                    db.opQueueDao().markFailed(
                        result.opId, result.error,
                        result.httpStatus.takeIf { it in DEFINITIVE_REJECTIONS },
                    )
                    // The opId belongs to exactly one of these tables; the other
                    // updates are harmless no-ops (WHERE id = opId matches none).
                    db.saleDao().markSync(result.opId, "failed", null, null, result.error)
                    db.refundDao().markSync(result.opId, "failed", null, result.error)
                    // message.send opId == the message id; flip its bubble to failed.
                    db.messageDao().markState(result.opId, "failed", result.error)
                    // Expense / purchase ops are keyed by their row id too.
                    db.expenseDao().markSync(result.opId, "failed", result.error)
                    db.purchaseDao().markSync(result.opId, "failed")
                }
            }
        }

        val state = db.syncStateDao().get()
        db.syncStateDao().put(
            SyncStateEntity(
                cursor = state?.cursor,
                lastPullAt = state?.lastPullAt,
                lastPushAt = Instant.now().toEpochMilli(),
            ),
        )
        return unresolved
    }

    /**
     * After a full pull, delete catalog rows and staff the server no longer
     * has: rows from a server the device was enrolled with before (staff whose
     * PINs must stop working, menu items that would be sold but rejected), or
     * rows deleted while the device was away. Anything referenced by an
     * unpushed op was created on this device and is kept until it syncs.
     */
    private suspend fun pruneToSnapshot(data: Map<String, List<JsonObject>>) = withContext(Dispatchers.IO) {
        val pending = db.opQueueDao().unpushed()
            .mapNotNull { op -> runCatching { Json.parseToJsonElement(op.payloadJson) as? JsonObject }.getOrNull() }
            .flatMap { p -> listOfNotNull(p.str("id"), p.str("clientId"), p.str("menuItemId"), p.str("productId")) }
            .toSet()
        val w = db.openHelper.writableDatabase
        for ((scope, table) in SNAPSHOT_SCOPES) {
            val rows = data[scope] ?: continue
            val keep = rows.filterNot { it.deleted() }.mapNotNull { it.str("id") }.toSet() + pending
            val stale = buildList {
                w.query("SELECT id FROM `$table`").use { c -> while (c.moveToNext()) c.getString(0).takeIf { it !in keep }?.let(::add) }
            }
            stale.chunked(500).forEach { ids ->
                w.execSQL("DELETE FROM `$table` WHERE id IN (${ids.joinToString(",") { "?" }})", ids.toTypedArray())
            }
        }
    }

    /**
     * Sales and cash ops name their shift by its local id. The server maps a
     * local id to its own only within the batch that opened the shift, so an
     * op pushed in a later batch (the usual case: the shift syncs at open, the
     * sales after) must carry the server id or it is rejected with "The
     * original register session is not open". Rewrite those references once
     * the shift has synced, and save the rewrite so a re-push sends the same
     * payload (the server's per-op idempotency compares payload hashes).
     */
    private suspend fun resolveSyncedSessions(ops: List<OpQueueEntity>): List<OpQueueEntity> {
        val serverIds = db.cashSessionDao().withServerIds().associate { it.id to it.serverId!! }
        if (serverIds.isEmpty()) return ops
        return ops.map { op ->
            // Already sent once: the server keyed its record of that attempt to the exact payload,
            // so a changed body would be refused as an idempotency conflict. Leave it as sent.
            if (op.attempts > 0) return@map op
            val payload = runCatching { Json.parseToJsonElement(op.payloadJson) as? JsonObject }.getOrNull()
                ?: return@map op
            val rewritten = JsonObject(
                payload.mapValues { (key, value) ->
                    val ref = (value as? JsonPrimitive)?.contentOrNull
                    if (key in SESSION_REF_KEYS && ref != null && ref in serverIds) JsonPrimitive(serverIds[ref]) else value
                },
            )
            if (rewritten == payload) return@map op
            val json = rewritten.toString()
            db.opQueueDao().updatePayload(op.opId, json)
            op.copy(payloadJson = json)
        }
    }

    private suspend fun applyMapping(opId: String, mapping: Map<String, String>?, finalNumbers: Map<String, String>?) {
        val invoiceId = mapping?.get("invoiceId")
        val invoiceNumber = finalNumbers?.get("invoiceNumber")
        if (invoiceId != null || invoiceNumber != null) {
            // opId keys a sale (sale.checkout) OR a refund (sale.refund/void) —
            // whichever it isn't, the update is a no-op.
            db.saleDao().markSync(opId, "pushed", invoiceId, invoiceNumber, null)
            db.refundDao().markSync(opId, "pushed", invoiceId, null)
        }
        // cash_session.open ops key the map by the session's clientId (== opId).
        mapping?.get(opId)?.let { serverId ->
            db.cashSessionDao().markSync(opId, "pushed", serverId)
        }
        // customer.upsert applied — the local row now mirrors the server.
        mapping?.get("customerId")?.let { db.customerDao().markSynced(it) }
        // reservation.* applied — mark the booking synced (keyed by its id, not
        // the opId, since seat/cancel/no-show ops have their own uuid).
        mapping?.get("reservationId")?.let { db.reservationDao().markSync(it, "synced") }
        // message.send applied — flip the optimistic bubble to 'sent'. The
        // authoritative seq arrives on the next pull (applyMessages).
        mapping?.get("messageId")?.let { db.messageDao().markState(it, "sent", null) }
        // expense.create / a drawer pay-out for an expense, and a purchase's stock.in —
        // all keyed by the local row id (== opId). No-ops for every other op.
        db.expenseDao().markSync(opId, "pushed", null)
        db.purchaseDao().markSync(opId, "pushed")
    }

    // ---------------------- pull-apply per scope ----------------------

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    private fun JsonObject.int(key: String): Int? = num(key)?.toInt()
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    private fun JsonObject.deleted(): Boolean = this["deletedAt"]?.let { it !is JsonNull } == true

    private suspend fun applyCategories(rows: List<JsonObject>) {
        val dao = db.menuDao()
        val pending = pendingIds("menuCategory.upsert", "menuCategory.delete")
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (id in pending) continue
            if (row.deleted()) { dao.deleteCategory(id); continue }
            dao.upsertCategories(
                listOf(
                    MenuCategoryEntity(
                        id = id,
                        name = row.str("name") ?: "",
                        sortOrder = row.int("sortOrder") ?: 0,
                        isActive = row.bool("isActive") ?: true,
                    ),
                ),
            )
        }
    }

    private suspend fun applyTaxes(rows: List<JsonObject>) {
        val dao = db.menuDao()
        val pending = pendingIds("tax.upsert", "tax.delete")
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (id in pending) continue
            if (row.deleted()) { dao.deleteTax(id); continue }
            dao.upsertTaxes(
                listOf(
                    TaxEntity(
                        id = id,
                        name = row.str("name") ?: "",
                        rate = row.num("rate") ?: 0.0,
                        isActive = row.bool("isActive") ?: true,
                    ),
                ),
            )
        }
    }

    private suspend fun applyModifierGroups(rows: List<JsonObject>) {
        val dao = db.menuDao()
        val pending = pendingIds("modifierGroup.upsert", "modifierGroup.delete")
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (id in pending) continue
            if (row.deleted()) { dao.deleteModifierGroup(id); continue }
            dao.upsertModifierGroups(
                listOf(
                    ModifierGroupEntity(
                        id = id,
                        name = row.str("name") ?: "",
                        groupType = row.str("groupType") ?: "ADD_ON",
                        minSelect = row.int("minSelect") ?: 0,
                        maxSelect = row.int("maxSelect") ?: 1,
                        sortOrder = row.int("sortOrder") ?: 0,
                        isActive = row.bool("isActive") ?: true,
                    ),
                ),
            )
            (row["modifiers"] as? JsonArray)?.let { mods ->
                dao.upsertModifiers(
                    mods.filterIsInstance<JsonObject>().mapNotNull { m ->
                        val mid = m.str("id") ?: return@mapNotNull null
                        ModifierEntity(
                            id = mid,
                            groupId = id,
                            name = m.str("name") ?: "",
                            kitchenPrintName = m.str("kitchenPrintName"),
                            priceDelta = m.num("priceDelta") ?: 0.0,
                            isDefault = m.bool("isDefault") ?: false,
                            sortOrder = m.int("sortOrder") ?: 0,
                            isActive = m.bool("isActive") ?: true,
                        )
                    },
                )
            }
        }
    }

    private suspend fun applyMenuItems(rows: List<JsonObject>) {
        val dao = db.menuDao()
        val pending = pendingIds("menuItem.upsert", "menuItem.delete")
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (id in pending) continue
            // MenuItem "delete" is isAvailable=false — still an upsert.
            dao.upsertItems(
                listOf(
                    MenuItemEntity(
                        id = id,
                        code = row.str("code"),
                        name = row.str("name") ?: "",
                        description = row.str("description"),
                        categoryId = row.str("categoryId"),
                        basePriceMajor = row.num("basePrice"), // already MAJOR on the server
                        taxId = row.str("taxId"),
                        image = row.str("image"),
                        isAvailable = row.bool("isAvailable") ?: true,
                        displayOrder = row.int("displayOrder") ?: 0,
                    ),
                ),
            )
            // Children arrive as the full aggregate → replace wholesale.
            dao.clearVariants(id)
            (row["variants"] as? JsonArray)?.let { variants ->
                dao.upsertVariants(
                    variants.filterIsInstance<JsonObject>().mapNotNull { v ->
                        val vid = v.str("id") ?: return@mapNotNull null
                        MenuItemVariantEntity(
                            id = vid,
                            menuItemId = id,
                            name = v.str("name") ?: "",
                            price = v.num("price") ?: 0.0,
                            sortOrder = v.int("sortOrder") ?: 0,
                            isActive = v.bool("isActive") ?: true,
                        )
                    },
                )
            }
            dao.clearItemModifierJoins(id)
            (row["modifierGroups"] as? JsonArray)?.let { joins ->
                dao.upsertItemModifierJoins(
                    joins.filterIsInstance<JsonObject>().mapNotNull { j ->
                        val gid = j.str("modifierGroupId") ?: (j["modifierGroup"] as? JsonObject)?.str("id")
                            ?: return@mapNotNull null
                        MenuItemModifierGroupEntity(menuItemId = id, modifierGroupId = gid, sortOrder = j.int("sortOrder") ?: 0)
                    },
                )
                // Groups embedded in the aggregate keep the local group tables warm.
                applyModifierGroups(joins.filterIsInstance<JsonObject>().mapNotNull { it["modifierGroup"] as? JsonObject })
            }
            dao.clearItemAccompanimentJoins(id)
            (row["accompanimentGroups"] as? JsonArray)?.let { joins ->
                for (j in joins.filterIsInstance<JsonObject>()) {
                    val group = j["accompanimentGroup"] as? JsonObject ?: continue
                    val gid = group.str("id") ?: continue
                    dao.upsertItemAccompanimentJoins(
                        listOf(MenuItemAccompanimentGroupEntity(menuItemId = id, accompanimentGroupId = gid, sortOrder = j.int("sortOrder") ?: 0)),
                    )
                    dao.upsertAccompanimentGroups(
                        listOf(
                            AccompanimentGroupEntity(
                                id = gid,
                                name = group.str("name") ?: "",
                                isRequired = group.bool("isRequired") ?: true,
                                minSelect = group.int("minSelect") ?: 1,
                                maxSelect = group.int("maxSelect") ?: 1,
                                sortOrder = group.int("sortOrder") ?: 0,
                                isActive = group.bool("isActive") ?: true,
                            ),
                        ),
                    )
                    (group["options"] as? JsonArray)?.let { options ->
                        dao.upsertAccompanimentOptions(
                            options.filterIsInstance<JsonObject>().mapNotNull { o ->
                                val oid = o.str("id") ?: return@mapNotNull null
                                AccompanimentOptionEntity(
                                    id = oid,
                                    groupId = gid,
                                    name = o.str("name") ?: "",
                                    priceImpact = o.num("priceImpact") ?: 0.0,
                                    isDefault = o.bool("isDefault") ?: false,
                                    sortOrder = o.int("sortOrder") ?: 0,
                                    isActive = o.bool("isActive") ?: true,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    private suspend fun applyTables(rows: List<JsonObject>) {
        val pending = pendingIds("posTable.upsert", "posTable.delete")
        db.tableDao().upsertAll(
            rows.mapNotNull { row ->
                val id = row.str("id") ?: return@mapNotNull null
                if (id in pending) return@mapNotNull null
                PosTableEntity(
                    id = id,
                    number = row.str("number") ?: "",
                    name = row.str("name"),
                    status = row.str("status") ?: "available",
                    sortOrder = row.int("sortOrder") ?: 0,
                )
            },
        )
    }

    private suspend fun applyRegisters(rows: List<JsonObject>) {
        // Skip registers with an un-pushed local edit, and preserve the
        // device-local isActive/sortOrder (the server has no such columns).
        val pending = pendingIds("cashRegister.upsert", "cashRegister.delete")
        val existing = db.registerDao().all().associateBy { it.id }
        db.registerDao().upsertAll(
            rows.mapNotNull { row ->
                val id = row.str("id") ?: return@mapNotNull null
                if (id in pending) return@mapNotNull null
                val local = existing[id]
                CashRegisterEntity(
                    id = id,
                    code = row.str("code") ?: local?.code ?: "",
                    name = row.str("name") ?: local?.name,
                    isActive = local?.isActive ?: true,
                    sortOrder = local?.sortOrder ?: 0,
                    defaultAccountId = row.str("defaultAccountId") ?: local?.defaultAccountId,
                    locationId = row.str("locationId") ?: local?.locationId,
                )
            },
        )
    }

    private suspend fun applyStaff(rows: List<JsonObject>) {
        val dao = db.staffDao()
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (row.deleted()) { dao.delete(id); config.clearPinFailures(id); continue }
            val pinHash = row.str("pinHash")
            // A new hash means the PIN was reset in the back office: that is
            // what unlocks a locked-out account. Merely syncing must not — the
            // till syncs on every launch and sale, so a restart would wipe the
            // backoff.
            if (dao.byId(id)?.pinHash != pinHash) config.clearPinFailures(id)
            val permissions = (row["roles"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.flatMap { r -> (r["permissions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList() }
                ?.toSet()
                ?: emptySet()
            dao.upsertAll(
                listOf(
                    StaffEntity(
                        id = id,
                        firstName = row.str("firstName") ?: "",
                        lastName = row.str("lastName"),
                        email = row.str("email") ?: "",
                        pinHash = pinHash,
                        permissions = permissions.joinToString(","),
                        isActive = row.bool("isActive") ?: true,
                    ),
                ),
            )
        }
    }

    private suspend fun applySettings(rows: List<JsonObject>) {
        // Keys with a queued setting.set op are mid-flight device edits — the
        // pull must not flip them back before the push lands (server wins once
        // the op is applied and the next pull re-delivers the merged value).
        val pendingKeys = db.opQueueDao().queuedOfType("setting.set")
            .mapNotNull { op -> parsePayload(op.payloadJson)?.str("key") }
            .toSet()
        db.settingsDao().upsertAll(
            rows.mapNotNull { row ->
                val key = row.str("key") ?: return@mapNotNull null
                if (key in pendingKeys) return@mapNotNull null
                SettingEntity(key = key, valueJson = row["value"]?.toString() ?: "null")
            },
        )
    }

    /**
     * Server customers (Partner rows, isCustomer only). Rows with a queued
     * local edit are skipped so an offline change isn't clobbered by a pull
     * that raced its push. Tombstones delete locally.
     */
    private suspend fun applyPartners(rows: List<JsonObject>) {
        val dao = db.customerDao()
        val pendingIds = (db.opQueueDao().queuedOfType("customer.upsert") + db.opQueueDao().queuedOfType("customer.delete"))
            .mapNotNull { op -> parsePayload(op.payloadJson)?.let { it.str("id") ?: it.str("clientId") } }
            .toSet()
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (id in pendingIds) continue
            if (row.deleted()) { dao.delete(id); continue }
            val customFields = row["customFields"] as? JsonObject
            dao.upsert(
                CustomerEntity(
                    id = id,
                    name = row.str("name") ?: "",
                    phone = row.str("phone"),
                    email = row.str("email"),
                    note = row.str("notes"),
                    loyaltyPoints = customFields?.int("loyaltyPoints") ?: 0,
                    createdAt = parseEpoch(row.str("createdAt")),
                    updatedAt = parseEpoch(row.str("updatedAt")),
                    syncStatus = "synced",
                ),
            )
        }
    }

    /**
     * Bookings for the floor. Terminal statuses (cancelled/no_show/completed)
     * are tombstones — dropped locally. Rows with a queued local op are skipped
     * so an offline booking/seat isn't clobbered by a pull that raced its push.
     */
    private suspend fun applyReservations(rows: List<JsonObject>) {
        val dao = db.reservationDao()
        val pendingIds = (
            db.opQueueDao().queuedOfType("reservation.create") +
                db.opQueueDao().queuedOfType("reservation.seat") +
                db.opQueueDao().queuedOfType("reservation.cancel") +
                db.opQueueDao().queuedOfType("reservation.noShow")
            ).mapNotNull { op -> parsePayload(op.payloadJson)?.let { it.str("reservationId") ?: it.str("id") } }
            .toSet()
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (id in pendingIds) continue
            val status = row.str("status") ?: "pending"
            if (status == "cancelled" || status == "no_show" || status == "completed") {
                dao.delete(id); continue
            }
            val tableId = row.str("tableId") ?: continue
            dao.upsert(
                ReservationEntity(
                    id = id,
                    tableId = tableId,
                    customerName = row.str("customerName") ?: "",
                    phone = row.str("phone"),
                    partySize = row.int("partySize") ?: 2,
                    startAt = parseEpoch(row.str("startAt")),
                    endAt = parseEpoch(row.str("endAt")),
                    status = status,
                    notes = row.str("notes"),
                    seatedOrderId = row.str("seatedOrderId"),
                    syncStatus = "synced",
                    updatedAt = parseEpoch(row.str("updatedAt")),
                ),
            )
        }
    }

    private fun parsePayload(payloadJson: String): JsonObject? =
        runCatching { Json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()

    /**
     * Ids of rows with a still-queued master-data op of the given types. A pull
     * that raced an un-pushed local edit must NOT overwrite these — the server
     * wins only once the op has applied and the next pull re-delivers it.
     */
    private suspend fun pendingIds(vararg types: String): Set<String> =
        types.flatMap { db.opQueueDao().queuedOfType(it) }
            .mapNotNull { op -> parsePayload(op.payloadJson)?.str("id") }
            .toSet()

    private suspend fun applyProducts(rows: List<JsonObject>) {
        val dao = db.productDao()
        val pending = pendingIds("product.upsert", "product.delete")
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (id in pending) continue
            if (row.deleted()) { dao.delete(id); continue }
            val category = row["category"] as? JsonObject
            val uom = row["uom"] as? JsonObject
            val tax = row["tax"] as? JsonObject
            dao.upsertAll(
                listOf(
                    ProductEntity(
                        id = id,
                        code = row.str("code"),
                        sku = row.str("sku"),
                        barcode = row.str("barcode"),
                        name = row.str("name") ?: "",
                        description = row.str("description"),
                        image = row.str("image"),
                        salesPrice = row.num("salesPrice") ?: 0.0,
                        costPrice = row.num("costPrice") ?: 0.0,
                        categoryId = row.str("categoryId"),
                        categoryName = category?.str("name"),
                        uomName = uom?.str("name"),
                        taxId = row.str("taxId"),
                        taxRate = tax?.num("rate") ?: 0.0,
                        taxInclusive = row.bool("taxInclusive") ?: false,
                        isActive = row.bool("isActive") ?: true,
                        isService = row.bool("isService") ?: false,
                        updatedAt = parseEpoch(row.str("updatedAt")),
                    ),
                ),
            )
        }
    }

    private suspend fun applyProductPackagings(rows: List<JsonObject>) {
        val dao = db.productPackagingDao()
        for (row in rows) {
            val id = row.str("id") ?: continue
            // No deletedAt on this model — a deactivated pack is removed locally.
            if (row.bool("isActive") == false) { dao.delete(id); continue }
            val productId = row.str("productId") ?: continue
            dao.upsertAll(
                listOf(
                    ProductPackagingEntity(
                        id = id,
                        productId = productId,
                        name = row.str("name") ?: "",
                        quantity = row.num("quantity") ?: 1.0,
                        barcode = row.str("barcode"),
                        isActive = true,
                    ),
                ),
            )
        }
    }

    private suspend fun applyProductCategories(rows: List<JsonObject>) {
        val dao = db.productCategoryDao()
        val pending = pendingIds("productCategory.upsert", "productCategory.delete")
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (id in pending) continue
            if (row.deleted()) { dao.delete(id); continue }
            dao.upsertAll(
                listOf(
                    ProductCategoryEntity(
                        id = id,
                        name = row.str("name") ?: "",
                        parentId = row.str("parentId"),
                    ),
                ),
            )
        }
    }

    /** Device-visible channels only (server already filtered by syncToDevices). */
    private suspend fun applyConversations(rows: List<JsonObject>) {
        val dao = db.conversationDao()
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (row.deleted()) { dao.delete(id); continue }
            val name = row.str("name") ?: row.str("kind") ?: "Channel"
            val updatedAt = parseEpoch(row.str("updatedAt"))
            dao.upsert(
                listOf(
                    ConversationEntity(
                        id = id,
                        title = name,
                        type = row.str("kind") ?: "channel",
                        // Preserve any newer local activity; a fresh row starts at updatedAt.
                        lastMessageAt = (dao.byId(id)?.lastMessageAt ?: 0L).coerceAtLeast(updatedAt),
                        lastMessagePreview = dao.byId(id)?.lastMessagePreview,
                        updatedAt = updatedAt,
                    ),
                ),
            )
        }
    }

    /**
     * Append-only messages, keyed by the seq cursor server-side. Guards:
     *  • skip ids with a still-queued local `message.send` (un-pushed optimistic
     *    bubble) so the pull can't clobber a mid-flight send;
     *  • preserve a locally-composed message's `outbound` direction on echo-back;
     *  • set the authoritative `seq` and flip to `sent`.
     * Retention (shared till): prune >30 days and keep ≤500 per conversation.
     */
    private suspend fun applyMessages(rows: List<JsonObject>) {
        val mDao = db.messageDao()
        val pending = pendingIdsByKey("message.send", "clientId", "id")
        val touched = mutableSetOf<String>()
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (id in pending) continue
            val conversationId = row.str("conversationId") ?: continue
            val existing = mDao.byId(id)
            val senderUserId = row.str("senderUserId")
            val senderName = existing?.senderName
                ?: senderUserId?.let { db.staffDao().byId(it)?.let { s -> listOfNotNull(s.firstName, s.lastName).joinToString(" ").trim() } }
                ?: (row.str("senderType") ?: "Staff")
            val occurredAt = parseEpoch(row.str("occurredAt"))
            mDao.upsert(
                listOf(
                    MessageEntity(
                        id = id,
                        conversationId = conversationId,
                        senderUserId = senderUserId,
                        senderName = senderName.ifBlank { "Staff" },
                        // A message we composed here keeps its outbound identity on echo-back.
                        direction = existing?.direction ?: "inbound",
                        body = row.str("body") ?: "",
                        occurredAt = occurredAt,
                        seq = row.str("syncSequence")?.toLongOrNull() ?: existing?.seq,
                        deliveryState = "sent",
                        lastError = null,
                    ),
                ),
            )
            db.conversationDao().touch(conversationId, occurredAt, (row.str("body") ?: "").take(80))
            touched += conversationId
        }
        // Retention.
        mDao.pruneOlderThan(Instant.now().toEpochMilli() - 30L * 24 * 60 * 60 * 1000)
        for (cid in touched) mDao.trimConversation(cid, 500)
    }

    // ---------------------- cash & inventory reference data ----------------------

    private suspend fun applyPaymentMethods(rows: List<JsonObject>) {
        db.cashInventoryDao().replacePaymentMethods(
            rows.mapNotNull { row ->
                val id = row.str("id") ?: return@mapNotNull null
                if (row.bool("isActive") == false) return@mapNotNull null
                PaymentMethodEntity(
                    id = id,
                    code = row.str("code") ?: id,
                    label = row.str("label") ?: row.str("kind") ?: "Tender",
                    kind = row.str("kind") ?: "cash",
                    accountId = row.str("accountId"),
                    accountName = row.str("accountName"),
                    requiresReference = row.bool("requiresReference") ?: false,
                    trackInShift = row.bool("trackInShift") ?: false,
                    sortOrder = row.int("sortOrder") ?: 0,
                )
            },
        )
    }

    private suspend fun applyLedgerAccounts(rows: List<JsonObject>) {
        db.cashInventoryDao().replaceAccounts(
            rows.mapNotNull { row ->
                val id = row.str("id") ?: return@mapNotNull null
                LedgerAccountEntity(
                    id = id,
                    code = row.str("code") ?: "",
                    name = row.str("name") ?: "",
                    categoryKey = row.str("categoryKey"),
                    classification = row.str("classification"),
                    isCashEquivalent = row.bool("isCashEquivalent") ?: false,
                    balance = row.num("balance"),
                    roles = (row["roles"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.joinToString(",") ?: "",
                )
            },
        )
    }

    private suspend fun applyExpenseCategories(rows: List<JsonObject>) {
        db.cashInventoryDao().replaceExpenseCategories(
            rows.mapNotNull { row ->
                val id = row.str("id") ?: return@mapNotNull null
                ExpenseCategoryEntity(id = id, name = row.str("name") ?: "", accountId = row.str("accountId"))
            },
        )
    }

    private suspend fun applyStockLocations(rows: List<JsonObject>) {
        db.cashInventoryDao().replaceLocations(
            rows.mapNotNull { row ->
                val id = row.str("id") ?: return@mapNotNull null
                StockLocationEntity(id = id, code = row.str("code") ?: "", name = row.str("name") ?: "", type = row.str("type") ?: "warehouse")
            },
        )
    }

    private suspend fun applyStockLevels(rows: List<JsonObject>) {
        db.cashInventoryDao().upsertStockLevels(
            rows.mapNotNull { row ->
                val id = row.str("id") ?: return@mapNotNull null
                StockLevelEntity(
                    id = id,
                    productId = row.str("productId") ?: return@mapNotNull null,
                    variantId = row.str("variantId"),
                    locationId = row.str("locationId") ?: return@mapNotNull null,
                    quantity = row.num("quantity") ?: 0.0,
                    updatedAt = parseEpoch(row.str("updatedAt")),
                )
            },
        )
    }

    /** Server suppliers merge into the device supplier list (device-made ones stay). */
    private suspend fun applySuppliers(rows: List<JsonObject>) {
        val dao = db.supplierDao()
        for (row in rows) {
            val id = row.str("id") ?: continue
            if (row.deleted()) { dao.delete(id); continue }
            dao.upsert(
                SupplierEntity(
                    id = id,
                    name = row.str("name") ?: "",
                    phone = row.str("phone"),
                    note = row.str("notes"),
                    createdAt = dao.byId(id)?.createdAt ?: parseEpoch(row.str("updatedAt")),
                ),
            )
        }
    }

    /** Like pendingIds but reads the id from any of the given payload keys in order. */
    private suspend fun pendingIdsByKey(type: String, vararg keys: String): Set<String> =
        db.opQueueDao().queuedOfType(type)
            .mapNotNull { op -> parsePayload(op.payloadJson)?.let { p -> keys.firstNotNullOfOrNull { p.str(it) } } }
            .toSet()

    /** Parse ISO-8601 date string or epoch-millis number to Long. */
    private fun parseEpoch(s: String?): Long {
        if (s == null) return 0L
        val n = s.toLongOrNull()
        if (n != null) return n
        return try { java.time.Instant.parse(s).toEpochMilli() } catch (_: Exception) { 0L }
    }

    companion object {
        /** Bump to force one full catalog re-pull after a local price-units fix. */
        const val CATALOG_UNITS_VERSION = 1

        /**
         * Rejections where the server committed nothing (bad input, business
         * rule, missing reference, dependency). Not 409 (may be "needs recovery"
         * for possibly-committed work), 401 (revocation) or 429/5xx (transient).
         */
        private val DEFINITIVE_REJECTIONS = setOf(400, 403, 404, 422, 424)

        /** Payload keys that reference a cash session by id (see [resolveSyncedSessions]). */
        private val SESSION_REF_KEYS = setOf("cashSessionId", "sessionId")

        /** Pull scopes that are complete when pulled without a cursor → their local table. */
        private val SNAPSHOT_SCOPES = mapOf(
            "staff" to "staff",
            "menuCategories" to "menu_categories",
            "menuItems" to "menu_items",
            "taxes" to "taxes",
            "posTables" to "pos_tables",
            "cashRegisters" to "cash_registers",
            "products" to "products",
            "productCategories" to "product_categories",
        )
    }
}

/** The server no longer accepts this device's token; the device has been wiped back to setup. */
class DeviceRevokedException : IllegalStateException("This device was revoked in the back office")
