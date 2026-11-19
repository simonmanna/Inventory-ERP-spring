package com.poscafe.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.PosDatabase
import com.poscafe.pos.data.local.entity.LocalCashSessionEntity
import com.poscafe.pos.data.repo.CashSessionRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "No sale" (LP2b): the drawer opening is queued as a `cash_drawer.open` op with
 * reason `no_sale` on the cashier's own open shift, the way pay-ins are queued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NoSaleTest {

    private lateinit var db: PosDatabase
    private lateinit var config: DeviceConfig

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).allowMainThreadQueries().build()
        config = DeviceConfig(context, context.getSharedPreferences("no-sale-test", Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() = db.close()

    private fun repo() = CashSessionRepository(
        db.cashSessionDao(), db.saleDao(), db.refundDao(), db.opQueueDao(), db.registerDao(),
        db.cashInventoryDao(), db.tabDao(), config,
    )

    private suspend fun openShift(cashier: String) = db.cashSessionDao().insert(
        LocalCashSessionEntity(
            id = "local-shift", cashRegisterId = "reg", actorUserId = cashier, openedAt = 0L, closedAt = null,
            openingFloat = 0.0, closingCounted = null, varianceReason = null, status = "open",
            syncStatus = "queued", serverId = null,
        ),
    )

    @Test
    fun payloadCarriesTheShiftReasonAndATrimmedNote() {
        val p = CashSessionRepository.noSalePayload("local-shift", "  change for a customer ")
        assertEquals("local-shift", p["sessionId"]!!.jsonPrimitive.content)
        assertEquals("no_sale", p["reason"]!!.jsonPrimitive.content)
        assertEquals("change for a customer", p["note"]!!.jsonPrimitive.content)
        assertFalse(CashSessionRepository.noSalePayload("s", "   ").containsKey("note"))
        assertFalse(CashSessionRepository.noSalePayload("s", null).containsKey("note"))
        try {
            CashSessionRepository.noSalePayload("s", "x".repeat(CashSessionRepository.NO_SALE_NOTE_MAX + 1))
            fail("a note over 500 characters is refused")
        } catch (e: IllegalArgumentException) {
            assertEquals("The note must be 500 characters or fewer", e.message)
        }
    }

    @Test
    fun noSaleIsQueuedAsACashDrawerOpenOp() = runBlocking {
        openShift("u1")
        repo().recordNoSale("u1", "till check", opId = "op-no-sale")
        val op = db.opQueueDao().queuedOfType("cash_drawer.open").single()
        assertEquals("op-no-sale", op.opId)
        assertEquals("u1", op.actorUserId)
        val payload = Json.parseToJsonElement(op.payloadJson).jsonObject
        assertEquals("local-shift", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("no_sale", payload["reason"]!!.jsonPrimitive.content)
        assertEquals("till check", payload["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun noSaleNeedsTheCashiersOwnOpenShift() = runBlocking {
        try {
            repo().recordNoSale("u1", null)
            fail("no open shift")
        } catch (e: IllegalStateException) {
            assertEquals("No open session", e.message)
        }
        openShift("u2")
        try {
            repo().recordNoSale("u1", null)
            fail("a colleague's shift")
        } catch (e: IllegalArgumentException) {
            assertEquals("This cash session belongs to a different cashier", e.message)
        }
        assertTrue(db.opQueueDao().queuedOfType("cash_drawer.open").isEmpty())
    }
}
