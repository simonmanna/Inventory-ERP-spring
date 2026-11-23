package com.poscafe.pos.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.account.AccountException
import com.poscafe.pos.data.account.AccountParsers
import com.poscafe.pos.data.account.AccountRepository
import com.poscafe.pos.data.account.SignInStep
import com.poscafe.pos.data.local.PosDatabase
import com.poscafe.pos.data.local.entity.StaffEntity
import com.poscafe.pos.data.remote.AccountApi
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

/** Mobile app shell: account sign-in, the company guard, staying signed in, one refresh on 401, device self-enrolment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccountRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: PosDatabase
    private lateinit var config: DeviceConfig
    private lateinit var auth: AuthRepository
    private lateinit var api: FakeApi
    private lateinit var repo: AccountRepository

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun http(code: Int, body: String = """{"message":"Invalid credentials"}""") =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    private inner class FakeApi : AccountApi {
        var user = "u1"
        var org = "org-1"
        val expired = mutableSetOf<String>()
        var refreshFails = false
        var refreshes = 0
        val seen = mutableListOf<String>()

        fun tokens(access: String) = json(
            """{"accessToken":"$access","refreshToken":"r-$access",
               "user":{"id":"$user","firstName":"Ann","lastName":"Admin","email":"ann@x"},
               "permissions":["organization:update","pos:checkout"],
               "organization":{"id":"$org","code":"DEMO","name":"Demo Cafe"}}""",
        )

        override suspend fun login(body: JsonObject): JsonObject = when ((body["password"] as JsonPrimitive).content) {
            "wrong" -> throw http(401)
            "mfa" -> json("""{"mfaToken":"m1","requiresMfa":true}""")
            else -> tokens("a1")
        }

        override suspend fun mfaLogin(body: JsonObject) = tokens("a1")

        override suspend fun refresh(body: JsonObject): JsonObject {
            refreshes++
            if (refreshFails) throw http(401)
            return tokens("a2")
        }

        override suspend fun registerDevice(auth: String, body: JsonObject): JsonObject {
            seen += auth
            return json("""{"id":"dev-9","name":"${(body["name"] as JsonPrimitive).content}","prefix":"D4","deviceToken":"tok-9"}""")
        }
    }

    private fun newRepo() = AccountRepository(api, auth, db.staffDao(), config)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).allowMainThreadQueries().build()
        config = DeviceConfig(context, context.getSharedPreferences("account-test", Context.MODE_PRIVATE))
        config.accountJson = null
        config.enrolledOrganizationId = null
        config.serverUrl = null
        config.deviceToken = null
        auth = AuthRepository(db.staffDao(), db.opQueueDao(), config)
        api = FakeApi()
        repo = newRepo()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun signIn(password: String = "pw") =
        (repo.authenticate(" DEMO ", "ann@x", password) as SignInStep.Tokens).account.let { repo.accept(it) }

    @Test
    fun `sign-in opens the app as that person and survives a restart`() = runBlocking {
        val a = signIn()
        assertEquals("Ann Admin", a.displayName)
        assertEquals("Demo Cafe", a.organizationName)
        assertEquals("u1", auth.appUser?.userId)
        assertFalse("no PIN yet: the till stays locked", auth.posUnlocked)
        assertEquals("DEMO", repo.lastOrganizationCode)
        assertEquals("ann@x", repo.lastEmail)

        // New process: the saved session comes back without a network call.
        val auth2 = AuthRepository(db.staffDao(), db.opQueueDao(), config)
        val repo2 = AccountRepository(api, auth2, db.staffDao(), config)
        assertTrue(repo2.restore())
        assertEquals("u1", auth2.appUser?.userId)
        assertEquals(setOf("organization:update", "pos:checkout"), auth2.appUser?.permissions)
    }

    @Test
    fun `wrong password shows the server's message and keeps nothing`() = runBlocking {
        val e = runCatching { repo.authenticate("DEMO", "ann@x", "wrong") }.exceptionOrNull() as AccountException
        assertEquals("Invalid credentials", e.message)
        assertEquals(401, e.status)
        assertNull(config.accountJson)
        assertNull(auth.appUser)
    }

    @Test
    fun `MFA first, then a session`() = runBlocking {
        val step = repo.authenticate("DEMO", "ann@x", "mfa") as SignInStep.NeedsMfa
        assertEquals("m1", step.mfaToken)
        repo.accept(repo.verifyMfa(step, "123456"))
        assertEquals("u1", repo.account.value?.userId)
    }

    @Test
    fun `an account of another company is refused on an enrolled device`() = runBlocking {
        config.serverUrl = "http://10.0.0.1:8080/api/v1/"
        config.deviceToken = "t"
        db.staffDao().upsertAll(listOf(StaffEntity("someone", "Bo", null, "bo@x", null, "", true)))
        try {
            signIn()
            fail("expected a refusal")
        } catch (e: AccountException) {
            assertTrue(e.message!!.contains("another company"))
        }
        assertNull(auth.appUser)
        assertNull(config.accountJson)

        // Known organization from an enrol-by-sign-in: the id decides.
        config.enrolledOrganizationId = "org-1"
        assertEquals("u1", signIn().userId)
        repo.signOut()
        config.enrolledOrganizationId = "org-2"
        assertTrue(runCatching { signIn() }.exceptionOrNull() is AccountException)
    }

    @Test
    fun `an expired token is refreshed once and the new one is kept`() = runBlocking {
        signIn()
        val seen = mutableListOf<String>()
        val out = repo.authed { bearer ->
            seen += bearer
            if (bearer == "Bearer a1") throw http(401)
            "ok"
        }
        assertEquals("ok", out)
        assertEquals(listOf("Bearer a1", "Bearer a2"), seen)
        assertEquals(1, api.refreshes)
        assertTrue(config.accountJson!!.contains("\"a2\""))
    }

    @Test
    fun `a refused refresh ends the session`() = runBlocking {
        signIn()
        api.refreshFails = true
        val e = runCatching { repo.authed<String> { throw http(401) } }.exceptionOrNull() as AccountException
        assertTrue(e.signedOut)
        assertNull(repo.account.value)
        assertNull(config.accountJson)
    }

    @Test
    fun `sign out forgets the session and everyone on the till`() = runBlocking {
        signIn()
        repo.signOut()
        assertNull(config.accountJson)
        assertNull(auth.current)
        assertFalse(newRepo().restore())
    }

    @Test
    fun `device self-enrolment uses the admin's bearer token`() = runBlocking {
        val a = (repo.authenticate("DEMO", "ann@x", "pw") as SignInStep.Tokens).account
        val d = repo.registerDevice(a, "Android · Pixel")
        assertEquals("dev-9", d.id)
        assertEquals("D4", d.prefix)
        assertEquals("tok-9", d.token)
        assertEquals(listOf("Bearer a1"), api.seen)
    }

    @Test
    fun `error messages are verbatim, with plain fallbacks`() {
        assertEquals("A; B", AccountParsers.errorMessage("""{"message":["A","B"]}""", 400))
        assertEquals("Wrong organization code, email or password.", AccountParsers.errorMessage("", 401))
        assertEquals("Too many attempts. Wait a minute and try again.", AccountParsers.errorMessage(null, 429))
    }

    @Test
    fun `a restored session the server refuses signs out at start`() = runBlocking {
        signIn()
        assertTrue("still valid: refreshed", repo.revalidate())
        api.refreshFails = true
        assertFalse(repo.revalidate())
        assertNull(auth.appUser)
        assertNull(config.accountJson)
    }
}
