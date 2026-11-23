package com.poscafe.pos.data.account

import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.dao.StaffDao
import com.poscafe.pos.data.remote.AccountApi
import com.poscafe.pos.data.repo.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** An account failure with a message fit to show; [signedOut] means the session is gone and sign-in is due. */
class AccountException(message: String, val signedOut: Boolean = false, val status: Int? = null) : Exception(message)

/** The signed-in ERP account, as persisted (encrypted) in [DeviceConfig.accountJson]. */
@Serializable
data class Account(
    val userId: String,
    val email: String? = null,
    val displayName: String,
    val permissions: List<String> = emptyList(),
    val organizationId: String? = null,
    val organizationCode: String,
    val organizationName: String? = null,
    val accessToken: String,
    val refreshToken: String,
) {
    fun asLoggedIn() = AuthRepository.LoggedIn(userId, displayName, permissions.toSet())
}

/** What `/auth/login` answered: a session, or a second factor is due. */
sealed interface SignInStep {
    data class Tokens(val account: Account) : SignInStep
    data class NeedsMfa(val mfaToken: String, val organizationCode: String) : SignInStep
}

/** A device the server just registered for this phone; [token] is shown only this once. */
data class RegisteredDevice(val id: String, val prefix: String, val token: String)

/**
 * The person's own ERP account on the phone (mobile app shell, 2026-11-23): sign in like the web app (organization
 * code, email, password, MFA when enrolled), stay signed in across restarts, and act online as that person with a
 * bearer token. It is the single owner of the tokens — CRM borrows them — so a rotated refresh token is never held
 * twice.
 *
 * The till itself stays offline-first: without a connection the app is opened with a staff PIN instead, and
 * nothing here is needed to sell. An account from another company than the one the device is enrolled in is refused,
 * so a phone never mixes two companies' work.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val api: AccountApi,
    private val auth: AuthRepository,
    private val staffDao: StaffDao,
    private val config: DeviceConfig,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()
    private val refreshLock = Mutex()

    /** Organization code and email last used, to pre-fill the sign-in form. */
    val lastOrganizationCode: String? get() = config.crmOrganizationCode
    val lastEmail: String? get() = config.lastAccountEmail

    /**
     * App start: bring back the saved session, if any, and open the app as that person. Works offline — the tokens
     * are only needed (and refreshed) when an online screen calls the server.
     */
    fun restore(): Boolean {
        val saved = config.accountJson ?: return false
        val a = runCatching { json.decodeFromString(Account.serializer(), saved) }.getOrNull()
        if (a == null) {
            config.accountJson = null
            return false
        }
        _account.value = a
        auth.signInApp(a.asLoggedIn())
        return true
    }

    /**
     * App start with a restored session: ask the server once whether it still stands (a refresh). A refused refresh —
     * the user was deactivated, the session revoked or expired — signs out everywhere and returns false; no connection
     * keeps the session (the app works offline) and returns true.
     */
    suspend fun revalidate(): Boolean {
        val cur = _account.value ?: return false
        return try {
            refresh(cur)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            if (e.signedOut) {
                signOut()
                false
            } else {
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    /** Step 1: organization code, email and password. Nothing is kept until [accept]. */
    suspend fun authenticate(organizationCode: String, email: String, password: String): SignInStep = call {
        val org = organizationCode.trim()
        val body = api.login(
            buildJsonObject {
                put("organizationCode", org)
                put("email", email.trim())
                put("password", password)
            },
        )
        config.crmOrganizationCode = org
        config.lastAccountEmail = email.trim()
        AccountParsers.login(body, org)
    }

    /** Step 1b: the 6-digit code from the authenticator app. */
    suspend fun verifyMfa(step: SignInStep.NeedsMfa, code: String): Account = call {
        val r = AccountParsers.login(
            api.mfaLogin(buildJsonObject { put("mfaToken", step.mfaToken); put("code", code.trim()) }),
            step.organizationCode,
        )
        (r as? SignInStep.Tokens)?.account ?: throw AccountException("The code was not accepted. Try again.")
    }

    /**
     * Step 2: open the app as [a]. On an enrolled device the account must belong to the device's company: the one it
     * was enrolled into, else one whose staff list (pulled from the server) holds this user.
     */
    suspend fun accept(a: Account): Account {
        if (config.isEnrolled) {
            val enrolledOrg = config.enrolledOrganizationId
            val foreign = if (enrolledOrg != null && a.organizationId != null) {
                enrolledOrg != a.organizationId
            } else {
                staffDao.all().isNotEmpty() && staffDao.byId(a.userId) == null
            }
            if (foreign) {
                throw AccountException("This account belongs to another company than this device. Sign in with an account of ${config.businessName}.")
            }
        }
        save(a)
        auth.signInApp(a.asLoggedIn())
        return a
    }

    /** Device self-enrolment (needs `organization:update`). The caller then pulls with the returned token. */
    suspend fun registerDevice(a: Account, name: String): RegisteredDevice = call {
        val r = api.registerDevice(
            "Bearer ${a.accessToken}",
            buildJsonObject { put("name", name); put("platform", "android") },
        )
        RegisteredDevice(
            id = r.str("id") ?: throw AccountException("The server did not return the device"),
            prefix = r.str("prefix") ?: "D1",
            token = r.str("deviceToken") ?: throw AccountException("The server did not return a device token"),
        )
    }

    /** Sign out of the app (the device stays enrolled; unsynced work stays queued). */
    fun signOut() {
        _account.value = null
        config.accountJson = null
        auth.logout()
    }

    /** The session, when it belongs to [userId]. */
    fun sessionFor(userId: String?): Account? = _account.value?.takeIf { userId != null && it.userId == userId }

    /**
     * Runs [block] with the bearer header; on a 401 refreshes the access token once and tries again. A refused
     * refresh ends the session ([AccountException.signedOut]); a network failure leaves it alone.
     */
    suspend fun <T> authed(block: suspend (String) -> T): T {
        val s = _account.value ?: throw AccountException("Sign in first.", signedOut = true)
        return try {
            block("Bearer ${s.accessToken}")
        } catch (e: HttpException) {
            if (e.code() != 401) throw e
            val fresh = refresh(s)
            block("Bearer ${fresh.accessToken}")
        }
    }

    private suspend fun refresh(used: Account): Account = refreshLock.withLock {
        val cur = _account.value ?: throw AccountException("Sign in first.", signedOut = true)
        if (cur.accessToken != used.accessToken) return@withLock cur // a parallel call already refreshed
        val r = try {
            AccountParsers.login(api.refresh(buildJsonObject { put("refreshToken", cur.refreshToken) }), cur.organizationCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) null else throw e
        }
        val t = (r as? SignInStep.Tokens)?.account?.takeIf { it.userId == cur.userId }
        if (t == null) {
            _account.value = null
            config.accountJson = null
            throw AccountException("Your session has expired. Sign in again.", signedOut = true)
        }
        cur.copy(
            accessToken = t.accessToken,
            refreshToken = t.refreshToken,
            permissions = t.permissions.ifEmpty { cur.permissions },
        ).also { save(it) }
    }

    private fun save(a: Account) {
        _account.value = a
        config.accountJson = json.encodeToString(Account.serializer(), a)
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AccountException) {
        throw e
    } catch (e: HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        throw AccountException(AccountParsers.errorMessage(body, e.code()), status = e.code())
    } catch (e: IOException) {
        throw AccountException(e.message?.takeIf { "HTTP" in it } ?: "No connection to the server.")
    } catch (e: Exception) {
        throw AccountException(e.message ?: "Something went wrong.")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

/** Reads `/auth` answers defensively: a missing or null field becomes a default, never a crash. */
object AccountParsers {
    private val json = Json { ignoreUnknownKeys = true }

    fun login(o: JsonObject, organizationCode: String): SignInStep {
        val mfa = o.str("mfaToken")
        if (mfa != null && (o.bool("requiresMfa") || o.str("accessToken") == null)) {
            return SignInStep.NeedsMfa(mfa, organizationCode)
        }
        val user = o["user"] as? JsonObject ?: JsonObject(emptyMap())
        val org = o["organization"] as? JsonObject
        val first = user.str("firstName")
        val last = user.str("lastName")
        val email = user.str("email")
        val name = listOfNotNull(first, last).joinToString(" ").ifBlank { email ?: "User" }
        return SignInStep.Tokens(
            Account(
                userId = user.str("id") ?: error("The server did not return the user"),
                email = email,
                displayName = name,
                permissions = (o["permissions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                organizationId = org?.str("id") ?: user.str("organizationId"),
                organizationCode = org?.str("code") ?: organizationCode,
                organizationName = org?.str("name"),
                accessToken = o.str("accessToken") ?: error("The server did not return a session"),
                refreshToken = o.str("refreshToken") ?: error("The server did not return a session"),
            ),
        )
    }

    /** The server's message verbatim when it sent one, else a plain fallback for the status. */
    fun errorMessage(body: String?, status: Int): String {
        val parsed = body?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val message = (parsed as? JsonObject)?.get("message")
        val text = when (message) {
            is JsonArray -> message.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("; ")
            is JsonPrimitive -> if (message is JsonNull) null else message.content
            else -> null
        }
        return text?.takeIf { it.isNotBlank() } ?: when (status) {
            401 -> "Wrong organization code, email or password."
            403 -> "You do not have permission for this."
            429 -> "Too many attempts. Wait a minute and try again."
            in 500..599 -> "The server had a problem. Try again in a moment."
            else -> "Request failed ($status)."
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
}
