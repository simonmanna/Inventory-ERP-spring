package com.poscafe.pos.data.crm

import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.remote.CrmApi
import com.poscafe.pos.data.repo.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A CRM failure with a message fit to show; [signedOut] means the CRM session is gone and the sign-in form is due. */
class CrmException(message: String, val signedOut: Boolean = false) : Exception(message)

/** What a salesperson types for a new lead on the till. */
data class LeadDraft(
    val name: String,
    val contactName: String = "",
    val companyName: String = "",
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
)

/**
 * Online-only CRM for the till (CRM enhancements T3c): quick lead capture, "My activities" and customer 360.
 *
 * The salesperson signs in with their own ERP account (`/auth/login`, MFA when enrolled). The session lives in memory
 * only — never on disk — and belongs to the person signed in on the till: when the till locks, idles out or another
 * PIN signs in, the CRM session is dropped. The account must be the same user as the PIN, so everything logged from
 * the till is attributed to the person holding it. Nothing is cached or queued: without a connection CRM says so.
 */
@Singleton
class CrmRepository @Inject constructor(
    private val api: CrmApi,
    private val auth: AuthRepository,
    private val config: DeviceConfig,
) {
    data class Session(
        val posUserId: String,
        val userId: String,
        val displayName: String,
        val permissions: Set<String>,
        val accessToken: String,
        val refreshToken: String,
    )

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val refreshLock = Mutex()

    /** The CRM session, only while the same person is still signed in on the till. */
    fun current(): Session? {
        val s = _session.value ?: return null
        if (auth.current?.userId != s.posUserId) {
            _session.value = null
            return null
        }
        return s
    }

    fun can(permission: String): Boolean = current()?.permissions?.contains(permission) == true

    fun signOut() {
        _session.value = null
    }

    /** Remembered organization code (not a secret) and the PIN user's email, to pre-fill the sign-in form. */
    var organizationCode: String?
        get() = config.crmOrganizationCode
        set(v) {
            config.crmOrganizationCode = v
        }

    suspend fun tillUserEmail(): String? {
        val id = auth.current?.userId ?: return null
        return auth.staffList().firstOrNull { it.id == id }?.email
    }

    suspend fun signIn(organizationCode: String, email: String, password: String): Result<CrmLoginResult> = call {
        val r = CrmParsers.login(
            api.login(
                buildJsonObject {
                    put("organizationCode", organizationCode.trim())
                    put("email", email.trim())
                    put("password", password)
                },
            ),
        )
        this.organizationCode = organizationCode.trim()
        if (r is CrmLoginResult.Tokens) accept(r)
        r
    }

    suspend fun verifyMfa(mfaToken: String, code: String): Result<CrmLoginResult> = call {
        val r = CrmParsers.login(
            api.mfaLogin(
                buildJsonObject {
                    put("mfaToken", mfaToken)
                    put("code", code.trim())
                },
            ),
        )
        if (r !is CrmLoginResult.Tokens) throw CrmException("The code was not accepted. Try again.")
        accept(r)
        r
    }

    private fun accept(t: CrmLoginResult.Tokens) {
        val till = auth.current ?: throw CrmException("Sign in to the till with your PIN first.", signedOut = true)
        if (t.userId != till.userId) {
            throw CrmException("Sign in with your own account — the one you use for your PIN on this till.", signedOut = true)
        }
        _session.value = Session(till.userId, t.userId, t.displayName, t.permissions, t.accessToken, t.refreshToken)
    }

    // ------------------------------------------------------------------ CRM calls

    suspend fun planner(state: String): Result<CrmPlanner> = call {
        CrmParsers.planner(authed { api.planner(it, mapOf("state" to state)) })
    }

    suspend fun markDone(activityId: String, outcome: String?): Result<Unit> = call {
        authed {
            api.done(it, activityId, buildJsonObject { outcome?.trim()?.takeIf { o -> o.isNotEmpty() }?.let { o -> put("outcome", o) } })
        }
        Unit
    }

    /** Possible duplicates of what is typed so far; empty when nothing identifying is typed yet. */
    suspend fun duplicates(d: LeadDraft): Result<List<CrmDuplicate>> = call {
        val q = buildMap<String, String> {
            d.phone.trim().takeIf { it.count(Char::isDigit) >= 7 }?.let { put("phone", it) }
            d.email.trim().takeIf { it.contains('@') }?.let { put("email", it) }
            d.companyName.trim().takeIf { it.length >= 3 }?.let { put("companyName", it) }
        }
        if (q.isEmpty()) emptyList() else CrmParsers.duplicates(authed { api.duplicates(it, q) })
    }

    /** Creates the lead (auto-assigned by the server's rules) and returns its name. */
    suspend fun createLead(d: LeadDraft): Result<String> = call {
        if (d.name.isBlank()) throw CrmException("Give the lead a name.")
        val body = buildJsonObject {
            put("name", d.name.trim())
            d.contactName.trim().takeIf { it.isNotEmpty() }?.let { put("contactName", it) }
            d.companyName.trim().takeIf { it.isNotEmpty() }?.let { put("companyName", it) }
            d.phone.trim().takeIf { it.isNotEmpty() }?.let { put("phone", it) }
            d.email.trim().takeIf { it.isNotEmpty() }?.let { put("email", it) }
            d.notes.trim().takeIf { it.isNotEmpty() }?.let { put("notes", it) }
        }
        val created = authed { api.createLead(it, body) }
        with(CrmParsers) { created.str("name") } ?: d.name.trim()
    }

    suspend fun customers(search: String): Result<List<CrmCustomerRow>> = call {
        val q = buildMap<String, String> {
            put("page", "1")
            put("pageSize", "50")
            search.trim().takeIf { it.isNotEmpty() }?.let { put("q", it) }
        }
        CrmParsers.customers(authed { api.customers(it, q) })
    }

    suspend fun customer(id: String): Result<CrmCustomer360> = call { CrmParsers.customer(authed { api.customer(it, id) }) }

    // ------------------------------------------------------------------ plumbing

    /** Runs [block] with the bearer header; on a 401 refreshes the access token once and tries again. */
    private suspend fun authed(block: suspend (String) -> JsonObject): JsonObject {
        val s = current() ?: throw CrmException("Sign in to CRM first.", signedOut = true)
        return try {
            block("Bearer ${s.accessToken}")
        } catch (e: HttpException) {
            if (e.code() != 401) throw e
            val fresh = refresh(s) ?: run {
                _session.value = null
                throw CrmException("Your CRM sign-in has expired. Sign in again.", signedOut = true)
            }
            block("Bearer ${fresh.accessToken}")
        }
    }

    private suspend fun refresh(used: Session): Session? = refreshLock.withLock {
        val cur = current() ?: return@withLock null
        if (cur.accessToken != used.accessToken) return@withLock cur // refreshed by a parallel call
        val r = try {
            CrmParsers.login(api.refresh(buildJsonObject { put("refreshToken", cur.refreshToken) }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        (r as? CrmLoginResult.Tokens)?.takeIf { it.userId == cur.userId }
            ?.let { cur.copy(accessToken = it.accessToken, refreshToken = it.refreshToken, permissions = it.permissions.ifEmpty { cur.permissions }) }
            ?.also { _session.value = it }
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: CrmException) {
        Result.failure(e)
    } catch (e: HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        Result.failure(CrmException(CrmParsers.errorMessage(body, e.code())))
    } catch (e: IOException) {
        Result.failure(CrmException(e.message?.takeIf { "HTTP" in it } ?: "No connection to the server. CRM needs the network."))
    } catch (e: Exception) {
        Result.failure(CrmException(e.message ?: "Something went wrong."))
    }
}
