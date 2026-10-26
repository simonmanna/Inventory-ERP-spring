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

    @Test
    fun `a definitive rejection is resent under a new key, a pending one keeps its key`() = runBlocking {
        queueOp("bad-input", 1)
        queueOp("maybe-committed", 2)
        api.reject["bad-input"] = 400 to "A session is already open on register MAIN-REG"
        api.reject["maybe-committed"] = 500 to "boom"
        repo().pushAll()
        assertEquals(2, db.opQueueDao().failedNow())

        // From now on the server answers both old keys with 409 "needs recovery".
        api.reject["bad-input"] = 409 to "This operation needs recovery"
        api.reject["maybe-committed"] = 409 to "This operation needs recovery"
        repo().retryRejected()
        repo().pushAll()

        val keys = api.pushed.last().ops.map { it.opId }
        assertTrue("400 goes out under a new key: $keys", keys.none { it == "bad-input" } && keys.size == 2)
        assertTrue("500 keeps its key", "maybe-committed" in keys)
        assertEquals(listOf("maybe-committed"), db.opQueueDao().unpushed().map { it.opId })

        // The 409 did not turn the 500 into a definitive rejection: it keeps its key again.
        repo().retryRejected("maybe-committed")
        repo().pushAll()
        assertEquals("maybe-committed", api.pushed.last().ops.single().opId)
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
        /** Wire op id -> (httpStatus, error) to reject instead of applying. */
        val reject = mutableMapOf<String, Pair<Int, String>>()
        var lastPullCursor: String? = "unset"
        val pushed = mutableListOf<PushRequest>()
        private val active = AtomicInteger()
        val maxConcurrent = AtomicInteger()

        private fun failIfNeeded() {
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
        }

        override suspend fun pull(cursor: String?, scopes: String?): PullResponse {
            failIfNeeded()
            lastPullCursor = cursor
            return PullResponse(data = pullData, cursor = "c2", serverTime = "2026-10-25T00:00:00Z")
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
                    reject[op.opId]?.let { (status, error) -> PushOpResult(op.opId, "failed", status, error) }
                        ?: PushOpResult(op.opId, "applied", 201)
                },
                lastPushSeq = body.ops.maxOf { it.deviceSeq },
                serverTime = "2026-10-25T00:00:00Z",
            )
        }
    }
}
