package com.poscafe.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.PosDatabase
import com.poscafe.pos.data.local.entity.LocalCashSessionEntity
import com.poscafe.pos.data.local.entity.LocalSaleEntity
import com.poscafe.pos.data.local.entity.OpQueueEntity
import com.poscafe.pos.data.local.entity.StaffEntity
import com.poscafe.pos.data.local.entity.SyncStateEntity
import com.poscafe.pos.data.remote.PullResponse
import com.poscafe.pos.data.remote.PushOpResult
import com.poscafe.pos.data.remote.PushRequest
import com.poscafe.pos.data.remote.PushResponse
import com.poscafe.pos.data.remote.SyncApi
import com.poscafe.pos.data.repo.DeviceRevokedException
import com.poscafe.pos.data.repo.DeviceTokenExpiredException
import com.poscafe.pos.data.repo.UpgradeRequiredException
import com.poscafe.pos.data.remote.OfflinePolicyView
import com.poscafe.pos.data.repo.SyncRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SyncRepositoryTest {

    private lateinit var db: PosDatabase
    private lateinit var config: DeviceConfig
    private val api = FakeApi()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).allowMainThreadQueries().build()
        config = DeviceConfig(context, context.getSharedPreferences("test-config", Context.MODE_PRIVATE))
        config.serverUrl = "http://192.168.1.10:8080/api/v1/"
        config.deviceId = "dev-1"
        config.deviceToken = "token"
        config.legacyMenuQueueRescaled = true
        config.catalogUnitsVersion = SyncRepository.CATALOG_UNITS_VERSION
    }

    @After
    fun tearDown() = db.close()

    private fun repo() = SyncRepository(api, db, config)

    private suspend fun seedStaff() = db.staffDao().upsertAll(
        listOf(StaffEntity("u1", "Ann", null, "ann@x", "\$2a\$10\$hash", "pos:sell", true)),
    )

    private suspend fun queueOp(id: String, seq: Long) = db.opQueueDao().enqueue(
        OpQueueEntity(id, seq, "sale.checkout", "u1", 0L, "{}", "queued", 0, null),
    )

    private suspend fun markSyncedBefore() =
        db.syncStateDao().put(SyncStateEntity(cursor = "c1", lastPullAt = 1L, lastPushAt = null))

    @Test
    fun `concurrent pushes never overlap`() = runBlocking {
        repeat(3) { queueOp("op-$it", it + 1L) }
        api.pushDelayMs = 50
        val repo = repo()
        listOf(async { repo.push() }, async { repo.pushAll() }, async { repo.push() }).awaitAll()
        assertEquals(1, api.maxConcurrent.get())
        assertEquals(0, db.opQueueDao().queuedNow())
    }

    @Test
    fun `401 after a successful sync wipes staff and forgets enrollment but keeps unsynced sales`() = runBlocking {
        seedStaff()
        markSyncedBefore()
        queueOp("sale-1", 1)
        api.status = 401

        try {
            repo().pushAll()
            fail("expected DeviceRevokedException")
        } catch (_: DeviceRevokedException) {
        }

        assertTrue(db.staffDao().all().isEmpty())
        assertEquals(1, db.opQueueDao().queuedNow())
        assertNull(config.deviceToken)
        assertFalse(config.isReady)
        assertTrue(config.revoked.value)
    }

    @Test
    fun `401 with nothing left to sync clears the whole database`() = runBlocking {
        seedStaff()
        markSyncedBefore()
        api.status = 401

        try {
            repo().pull()
            fail("expected DeviceRevokedException")
        } catch (_: DeviceRevokedException) {
        }

        assertNull(db.syncStateDao().get())
        assertTrue(config.revoked.value)
    }

    @Test
    fun `401 during first enrollment is a plain error and wipes nothing`() = runBlocking {
        seedStaff()
        api.status = 401

        try {
            repo().pull()
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(401, e.code())
        }

        assertEquals(1, db.staffDao().all().size)
        assertEquals("token", config.deviceToken)
        assertFalse(config.revoked.value)
    }

    @Test
    fun `changing server saves the address only when the server accepts the token`() = runBlocking {
        assertNull(repo().changeServer("http://192.168.1.20:8088/api/v1"))
        assertEquals("http://192.168.1.20:8088/api/v1/", config.serverUrl)

        seedStaff()
        markSyncedBefore()
        api.status = 401
        val error = repo().changeServer("http://10.0.0.9:3001/api/v1")

        assertTrue(error.orEmpty().contains("does not recognise this device"))
        assertEquals("http://192.168.1.20:8088/api/v1/", config.serverUrl)
        // A wrong address is never mistaken for revocation.
        assertEquals(1, db.staffDao().all().size)
        assertFalse(config.revoked.value)
    }

    @Test
    fun `re-enroll forgets the token but keeps unsynced sales and staff`() = runBlocking {
        seedStaff()
        queueOp("sale-1", 1)

        repo().startReenroll()

        assertNull(config.deviceToken)
        assertTrue(config.setupRequested.value)
        assertEquals(1, db.opQueueDao().queuedNow())
        assertEquals(1, db.staffDao().all().size)
    }

    @Test
    fun `re-enrolment pulls a full snapshot and prunes the previous server's staff`() = runBlocking {
        seedStaff() // u1, from the old server
        markSyncedBefore() // old server's cursor "c1"
        api.pullData = mapOf(
            "staff" to listOf(staffRow("u2")),
            "menuItems" to emptyList(),
        )
        val repo = repo()

        repo.resetForEnrollment()
        repo.pull()

        assertNull("first pull of a new enrolment must not reuse the old cursor", api.lastPullCursor)
        assertEquals(listOf("u2"), db.staffDao().all().map { it.id })
    }

    @Test
    fun `incremental pulls never prune`() = runBlocking {
        seedStaff()
        markSyncedBefore()
        api.pullData = mapOf("staff" to listOf(staffRow("u2")))

        repo().pull()

        assertEquals("c1", api.lastPullCursor)
        assertEquals(setOf("u1", "u2"), db.staffDao().all().map { it.id }.toSet())
    }

    @Test
    fun `snapshot prune keeps rows referenced by unsynced ops`() = runBlocking {
        seedStaff()
        db.opQueueDao().enqueue(
            OpQueueEntity("op-u1", 1, "staff.pinChange", "u1", 0L, """{"id":"u1"}""", "queued", 0, null),
        )
        api.pullData = mapOf("staff" to listOf(staffRow("u2")))

        repo().pull() // no cursor yet → full snapshot

        assertEquals(setOf("u1", "u2"), db.staffDao().all().map { it.id }.toSet())
    }

    private fun staffRow(id: String) = buildJsonObject {
        put("id", id)
        put("firstName", "Staff $id")
        put("email", "$id@x")
        put("pinHash", "\$2a\$10\$hash")
        put("isActive", true)
    }

    @Test
    fun `sales pushed after their shift synced carry the server shift id`() = runBlocking {
        db.cashSessionDao().insert(
            LocalCashSessionEntity(
                id = "local-shift", cashRegisterId = "reg", actorUserId = "u1", openedAt = 0L, closedAt = null,
                openingFloat = 0.0, closingCounted = null, varianceReason = null, status = "open",
                syncStatus = "pushed", serverId = "server-shift",
            ),
        )
        db.opQueueDao().enqueue(
            OpQueueEntity("sale-1", 2, "sale.checkout", "u1", 0L, """{"cashSessionId":"local-shift","total":5000}""", "queued", 0, null),
        )

        repo().pushAll()

        val sent = api.pushed.single().ops.single().payload.toString()
        assertTrue(sent, sent.contains("\"cashSessionId\":\"server-shift\""))
        // Saved, so a retry sends the identical payload.
        assertTrue(db.opQueueDao().unpushed().isEmpty())
    }

    @Test
    fun `discarding a rejected sale unblocks shift close and drops it from totals`() = runBlocking {
        db.saleDao().insert(
            LocalSaleEntity(
                id = "sale-x", occurredAt = 0L, actorUserId = "u1", cashSessionLocalId = "shift", tableId = null,
                orderType = "takeaway", provisionalNumber = "D2-000001", subtotal = 5000.0, taxAmount = 0.0,
                total = 5000.0, tendersJson = "[]", linesJson = "[]", syncStatus = "failed",
                serverInvoiceId = null, finalInvoiceNumber = null, lastError = "409",
            ),
        )
        db.opQueueDao().enqueue(OpQueueEntity("sale-x", 1, "sale.checkout", "u1", 0L, "{}", "failed", 2, "409"))
        queueOp("still-queued", 2)

        assertFalse("queued ops cannot be discarded", repo().discardRejected("still-queued", "Admin User"))
        assertTrue(repo().discardRejected("sale-x", "Admin User"))

        assertEquals(0, db.opQueueDao().failedNow())
        assertEquals(listOf("still-queued"), db.opQueueDao().unpushed().map { it.opId })
        assertEquals(0.0, db.saleDao().sessionTotal("shift"), 0.0)
        val sale = db.saleDao().byId("sale-x")!!
        assertEquals("discarded", sale.syncStatus)
        assertTrue(sale.lastError!!.startsWith("Discarded by Admin User"))
        assertFalse("second discard is a no-op", repo().discardRejected("sale-x", "Admin User"))
    }

    // ---------------- ADR-S083 O6: protocol 2 ----------------

    private suspend fun failedSale(id: String) = db.saleDao().insert(
        LocalSaleEntity(
            id = id, occurredAt = 0L, actorUserId = "u1", cashSessionLocalId = "shift", tableId = null,
            orderType = "takeaway", provisionalNumber = "D2-000009", subtotal = 10.0, taxAmount = 0.0,
            total = 10.0, tendersJson = "[]", linesJson = "[]", syncStatus = "queued",
            serverInvoiceId = null, finalInvoiceNumber = null, lastError = null,
        ),
    )

    @Test
    fun `a definitive rejection is never re-sent or re-keyed - a correction is a new op that supersedes it`() = runBlocking {
        failedSale("sale-1")
        db.opQueueDao().enqueue(OpQueueEntity("sale-1", 1, "sale.checkout", "u1", 0L, "{\"a\":1}", "queued", 0, null))
        api.reject["sale-1"] = Rejection(422, "No open shift", "BUSINESS_RULE", false)
        repo().pushAll()
        val failed = db.opQueueDao().byId("sale-1")!!
        assertEquals("failed", failed.status)
        assertEquals("BUSINESS_RULE", failed.failureCode)
        assertFalse(failed.retryable)

        repo().retryRejected()
        repo().pushAll()
        assertEquals("a definitive rejection is not retried", 1, api.pushed.size)

        val newId = repo().resubmitRejected("sale-1", "Admin User")!!
        assertEquals("superseded", db.opQueueDao().byId("sale-1")!!.status)
        val correction = db.opQueueDao().byId(newId)!!
        assertEquals("sale-1", correction.supersedesOpId)
        assertEquals("sale-1", correction.rowId)
        assertEquals(2L, correction.deviceSeq)
        assertEquals("{\"a\":1}", correction.payloadJson)
        assertNull("only once", repo().resubmitRejected("sale-1", "Admin User"))

        api.reject.clear()
        api.finalNumber = "INV-0042"
        repo().pushAll()
        val wire = api.pushed.last().ops.single()
        assertEquals(newId, wire.opId)
        assertEquals("sale-1", wire.supersedesOpId)
        assertEquals(2L, wire.deviceSeq)
        val sale = db.saleDao().byId("sale-1")!!
        assertEquals("the correction's result lands on the original sale", "pushed", sale.syncStatus)
        assertEquals("INV-0042", sale.finalInvoiceNumber)
    }

    @Test
    fun `a retryable failure stays queued under the same opId, waits out a backoff and holds later ops back`() = runBlocking {
        queueOp("flaky", 1)
        queueOp("after", 2)
        api.reject["flaky"] = Rejection(500, "boom", "SERVER_ERROR", true)
        repo().pushAll()
        val op = db.opQueueDao().byId("flaky")!!
        assertEquals("queued", op.status)
        assertTrue(op.retryable)
        assertTrue(op.nextAttemptAt > System.currentTimeMillis())
        // "after" was in the same batch and applied; a new op queued now must wait behind "flaky".
        queueOp("later", 3)
        repo().pushAll()
        assertEquals("nothing is sent while the earliest op backs off", 1, api.pushed.size)

        db.openHelper.writableDatabase.execSQL("UPDATE op_queue SET nextAttemptAt = 0")
        api.reject.clear()
        repo().pushAll()
        assertEquals(listOf("flaky", "later"), api.pushed.last().ops.map { it.opId })
        assertTrue(db.opQueueDao().unpushed().isEmpty())
    }

    @Test
    fun `a retryable failure is parked after the automatic attempts and a person retries it with the same opId`() = runBlocking {
        queueOp("dep", 1)
        api.reject["dep"] = Rejection(424, "Depends on failed op", "DEPENDENCY_FAILED", true)
        var rounds = 0
        while (db.opQueueDao().byId("dep")!!.status == "queued" && rounds < 20) {
            db.openHelper.writableDatabase.execSQL("UPDATE op_queue SET nextAttemptAt = 0")
            repo().pushAll()
            rounds++
        }
        assertEquals(SyncRepository.MAX_AUTO_RETRIES, api.pushed.size)
        val parked = db.opQueueDao().byId("dep")!!
        assertEquals("failed", parked.status)
        assertTrue(parked.retryable)

        api.reject.clear()
        repo().retryRejected("dep")
        repo().pushAll()
        assertEquals("dep", api.pushed.last().ops.single().opId)
        assertTrue(db.opQueueDao().unpushed().isEmpty())
    }

    @Test
    fun `an op re-keyed by an older build keeps going out under the key the server knows`() = runBlocking {
        db.opQueueDao().enqueue(
            OpQueueEntity("local", 1, "sale.checkout", "u1", 0L, "{}", "queued", 1, null, wireOpId = "wire-2"),
        )
        repo().pushAll()
        assertEquals("wire-2", api.pushed.last().ops.single().opId)
        assertEquals("pushed", db.opQueueDao().byId("local")!!.status)
    }

    @Test
    fun `after a backup restore unsent ops are renumbered above every sequence the server saw`() = runBlocking {
        config.seqHighWater = 40
        db.opQueueDao().enqueue(OpQueueEntity("old-sent", 7, "sale.checkout", "u1", 0L, "{}", "pushed", 1, null))
        queueOp("a", 8)
        queueOp("b", 9)
        config.renumberPending = true
        repo().pushAll()
        assertEquals(listOf(41L, 42L), api.pushed.last().ops.map { it.deviceSeq })
        assertEquals(listOf("a", "b"), api.pushed.last().ops.map { it.opId })
        assertFalse(config.renumberPending)
        assertEquals(42L, config.seqHighWater)
    }

    @Test
    fun `an expired token stops the pull without wiping anything, and the push before it still runs`() = runBlocking {
        seedStaff()
        markSyncedBefore()
        queueOp("sale", 1)
        repo().pushAll()
        api.pullStatus = 401
        api.errorBody = "{\"message\":\"This device's token expired — register the device again\"}"
        try {
            repo().pull()
            fail("expected DeviceTokenExpiredException")
        } catch (_: DeviceTokenExpiredException) {
        }
        assertEquals(SyncRepository.PROBLEM_TOKEN_EXPIRED, config.syncProblem.value)
        assertTrue("enrollment kept", config.isEnrolled)
        assertFalse(config.revoked.value)
        assertEquals("staff kept", 1, db.staffDao().all().size)
        assertEquals(1, api.pushed.size)
    }

    @Test
    fun `426 on pull asks for an upgrade and wipes nothing`() = runBlocking {
        seedStaff()
        markSyncedBefore()
        api.pullStatus = 426
        try {
            repo().pull()
            fail("expected UpgradeRequiredException")
        } catch (_: UpgradeRequiredException) {
        }
        assertEquals(SyncRepository.PROBLEM_UPGRADE, config.syncProblem.value)
        assertTrue(config.isEnrolled)
        api.pullStatus = 200
        repo().pull()
        assertNull("a good pull clears it", config.syncProblem.value)
    }

    @Test
    fun `a protocol-2 pull rotates the token first and applies the offline policy`() = runBlocking {
        api.pullExtra = { it.copy(deviceToken = "rotated", deviceTokenExpiresAt = "2026-12-21T00:00:00.000Z",
            serverId = "hq", offlinePolicy = OfflinePolicyView(24, 10, 3, 2)) }
        repo().pull()
        assertEquals("rotated", config.deviceToken)
        assertEquals("2026-12-21T00:00:00.000Z", config.deviceTokenExpiresAt)
        assertEquals("hq", config.serverId)
        assertEquals(DeviceConfig.OfflinePolicy(24, 10, 3, 2), config.offlinePolicy)
        assertNotNull(config.lastPullAt)
    }

    @Test
    fun `protocol-2 tombstones remove menu items and registers`() = runBlocking {
        api.pullData = mapOf(
            "menuItems" to listOf(buildJsonObject { put("id", "m1"); put("name", "Tea"); put("basePrice", 2) }),
            "cashRegisters" to listOf(buildJsonObject { put("id", "r1"); put("code", "MAIN") }),
        )
        repo().pull()
        assertNotNull(db.menuDao().itemById("m1"))
        assertEquals(1, db.registerDao().all().size)
        api.pullData = mapOf(
            "menuItems" to listOf(buildJsonObject { put("id", "m1"); put("deletedAt", "2026-11-21T00:00:00Z") }),
            "cashRegisters" to listOf(buildJsonObject { put("id", "r1"); put("deletedAt", "2026-11-21T00:00:00Z") }),
        )
        repo().pull()
        assertNull(db.menuDao().itemById("m1"))
        assertEquals(0, db.registerDao().all().size)
    }

    @Test
    fun `a pull keeps PIN lockouts unless the server sends a new PIN`() = runBlocking {
        api.pullData = mapOf("staff" to listOf(staffRow("u2")))
        repo().pull()
        repeat(3) { config.recordPinFailure("u2") }

        repo().pull()
        assertEquals("same hash: lockout survives a sync (e.g. on restart)", 3, config.pinFailures("u2"))

        val reset = JsonObject(staffRow("u2") + ("pinHash" to JsonPrimitive("reset-hash")))
        api.pullData = mapOf("staff" to listOf(reset))
        repo().pull()
        assertEquals("PIN reset in the back office unlocks", 0, config.pinFailures("u2"))
    }

    class FakeApi : SyncApi {
        var status = 200
        var pushDelayMs = 0L
        var pullData: Map<String, List<JsonObject>> = emptyMap()
        /** Wire op id -> rejection to answer instead of applying. */
        val reject = mutableMapOf<String, Rejection>()
        var pullStatus = 200
        var errorBody = "{}"
        var finalNumber: String? = null
        var pullExtra: (PullResponse) -> PullResponse = { it }
        var lastPullCursor: String? = "unset"
        val pushed = mutableListOf<PushRequest>()
        private val active = AtomicInteger()
        val maxConcurrent = AtomicInteger()

        private fun failIfNeeded(code: Int = status) {
            if (code != 200) throw HttpException(Response.error<Any>(code, errorBody.toResponseBody()))
        }

        override suspend fun pull(cursor: String?, scopes: String?): PullResponse {
            failIfNeeded()
            failIfNeeded(pullStatus)
            lastPullCursor = cursor
            return pullExtra(PullResponse(data = pullData, cursor = "c2", serverTime = "2026-10-25T00:00:00Z"))
        }

        override suspend fun push(batchId: String, body: PushRequest): PushResponse {
            failIfNeeded()
            pushed += body
            maxConcurrent.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                delay(pushDelayMs)
            } finally {
                active.decrementAndGet()
            }
            return PushResponse(
                results = body.ops.map { op ->
                    reject[op.opId]?.let { r -> PushOpResult(op.opId, "failed", r.status, r.error, code = r.code, retryable = r.retryable) }
                        ?: PushOpResult(op.opId, "applied", 201, finalNumbers = finalNumber?.let { mapOf("invoiceNumber" to it) })
                },
                lastPushSeq = body.ops.maxOf { it.deviceSeq },
                serverTime = "2026-10-25T00:00:00Z",
            )
        }
    }
}

data class Rejection(val status: Int, val error: String, val code: String? = null, val retryable: Boolean? = null)
