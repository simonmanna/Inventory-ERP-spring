package com.poscafe.pos.crm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import at.favre.lib.crypto.bcrypt.BCrypt
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.crm.CrmException
import com.poscafe.pos.data.crm.CrmLoginResult
import com.poscafe.pos.data.crm.CrmRepository
import com.poscafe.pos.data.crm.LeadDraft
import com.poscafe.pos.data.local.PosDatabase
import com.poscafe.pos.data.local.entity.StaffEntity
import com.poscafe.pos.data.remote.CrmApi
import com.poscafe.pos.data.repo.AuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/** The till's CRM session rules (CRM enhancements T3c): same person as the PIN, memory only, one refresh on 401. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CrmRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: PosDatabase
    private lateinit var auth: AuthRepository
    private lateinit var api: FakeApi
    private lateinit var repo: CrmRepository

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun tokens(user: String, access: String) =
        json("""{"accessToken":"$access","refreshToken":"r-$access","user":{"id":"$user","firstName":"Ann"},"permissions":["crm:activity:read","crm:lead:write"]}""")

    private fun http(code: Int, body: String = """{"message":"nope"}""") =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    /** Answers like the server: a token is valid until [expired] says otherwise. */
    private inner class FakeApi : CrmApi {
        var loginAs = "u1"
        var expired = mutableSetOf<String>()
        var refreshFails = false
        val seenAuth = mutableListOf<String>()
        var lastLead: JsonObject? = null

        override suspend fun login(body: JsonObject) =
            if ((body["password"] as JsonPrimitive).content == "mfa") json("""{"mfaToken":"m1","requiresMfa":true}""") else tokens(loginAs, "a1")

        override suspend fun mfaLogin(body: JsonObject) = tokens(loginAs, "a1")

        override suspend fun refresh(body: JsonObject): JsonObject {
            if (refreshFails) throw http(401)
            return tokens(loginAs, "a2")
        }

        private fun check(auth: String) {
            seenAuth += auth
            if (auth.removePrefix("Bearer ") in expired) throw http(401)
        }

        override suspend fun planner(auth: String, query: Map<String, String>): JsonObject {
            check(auth)
            return json("""{"data":[],"counts":{"overdue":0,"today":0,"upcoming":0}}""")
        }

        override suspend fun done(auth: String, id: String, body: JsonObject): JsonObject { check(auth); return json("{}") }
        override suspend fun duplicates(auth: String, query: Map<String, String>): JsonObject { check(auth); return json("""{"deals":[],"partners":[]}""") }
        override suspend fun createLead(auth: String, body: JsonObject): JsonObject {
            check(auth)
            lastLead = body
            return json("""{"id":"d1","name":"${(body["name"] as JsonPrimitive).content}"}""")
        }
        override suspend fun customers(auth: String, query: Map<String, String>): JsonObject { check(auth); return json("""{"data":[]}""") }
        override suspend fun customer(auth: String, id: String): JsonObject { check(auth); throw http(404, """{"message":"Partner not found"}""") }
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).allowMainThreadQueries().build()
        val hash = BCrypt.withDefaults().hashToString(4, "1234".toCharArray())
        db.staffDao().upsertAll(listOf(StaffEntity("u1", "Ann", null, "ann@x", hash, "crm:lead:write", true)))
        val config = DeviceConfig(context, context.getSharedPreferences("crm-test", Context.MODE_PRIVATE))
        auth = AuthRepository(db.staffDao(), db.opQueueDao(), config)
        api = FakeApi()
        repo = CrmRepository(api, auth, config)
        assertTrue(auth.loginWithPin("u1", "1234").isSuccess)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `signs in as the PIN user and pre-fills the email`() = runBlocking {
        assertEquals("ann@x", repo.tillUserEmail())
        val r = repo.signIn(" DEMO ", "ann@x", "pw")
        assertTrue(r.getOrNull() is CrmLoginResult.Tokens)
        assertEquals("u1", repo.current()?.userId)
        assertEquals("DEMO", repo.organizationCode)
    }

    @Test
    fun `another person's account is refused`() = runBlocking {
        api.loginAs = "someone-else"
        val r = repo.signIn("DEMO", "boss@x", "pw")
        assertTrue((r.exceptionOrNull() as CrmException).signedOut)
        assertNull(repo.current())
    }

    @Test
    fun `MFA first, then a session`() = runBlocking {
        val r = repo.signIn("DEMO", "ann@x", "mfa").getOrThrow()
        assertEquals(CrmLoginResult.NeedsMfa("m1"), r)
        assertNull(repo.current())
        repo.verifyMfa("m1", "123456").getOrThrow()
        assertNotNull(repo.current())
    }

    @Test
    fun `locking the till ends the CRM session`() = runBlocking {
        repo.signIn("DEMO", "ann@x", "pw").getOrThrow()
        auth.logout()
        assertNull(repo.current())
        assertTrue((repo.planner("today").exceptionOrNull() as CrmException).signedOut)
    }

    @Test
    fun `an expired access token is refreshed once`() = runBlocking {
        repo.signIn("DEMO", "ann@x", "pw").getOrThrow()
        api.expired += "a1"
        assertTrue(repo.planner("today").isSuccess)
        assertEquals(listOf("Bearer a1", "Bearer a2"), api.seenAuth)
        assertEquals("a2", repo.current()?.accessToken)
    }

    @Test
    fun `a failed refresh signs out`() = runBlocking {
        repo.signIn("DEMO", "ann@x", "pw").getOrThrow()
        api.expired += "a1"
        api.refreshFails = true
        val e = repo.planner("today").exceptionOrNull() as CrmException
        assertTrue(e.signedOut)
        assertNull(repo.current())
    }

    @Test
    fun `lead body carries only what was typed and server errors are shown verbatim`() = runBlocking {
        repo.signIn("DEMO", "ann@x", "pw").getOrThrow()
        assertEquals("Coffee for Acme", repo.createLead(LeadDraft(name = " Coffee for Acme ", phone = "0772 000111")).getOrThrow())
        assertEquals(setOf("name", "phone"), api.lastLead!!.keys)
        assertEquals("Partner not found", repo.customer("x").exceptionOrNull()?.message)
        assertEquals("Give the lead a name.", repo.createLead(LeadDraft(name = " ")).exceptionOrNull()?.message)
    }
}
