package com.poscafe.pos.data.crm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A planned activity of "My activities" (`GET /crm/planner`). */
data class CrmActivity(
    val id: String,
    val type: String,
    val title: String,
    val dueAt: String?,
    /** overdue | today | planned | done */
    val state: String,
    val dealId: String?,
    val dealName: String?,
    val partnerId: String?,
    val partnerName: String?,
)

data class CrmPlanner(val activities: List<CrmActivity>, val overdue: Int, val today: Int, val upcoming: Int)

/** A row of the customer list (`GET /crm/customers`). */
data class CrmCustomerRow(
    val id: String,
    val name: String,
    val code: String?,
    val phone: String?,
    val email: String?,
    val openOpportunities: Int,
    val openValue: BigDecimal,
    val overdueReceivables: BigDecimal,
)

data class CrmContact(val name: String, val position: String?, val phone: String?, val email: String?, val isPrimary: Boolean)

data class CrmDealRow(
    val id: String,
    val kind: String,
    val name: String,
    val status: String,
    val stageName: String?,
    val amount: BigDecimal,
    val currencyCode: String?,
)

data class CrmHistoryRow(val type: String, val title: String, val status: String, val at: String?)

data class CrmInvoiceRow(
    val number: String,
    val status: String,
    val issueDate: String?,
    val total: BigDecimal,
    val residual: BigDecimal,
    val paymentStatus: String?,
)

/** Customer 360 (`GET /crm/customers/{id}`), reduced to what the till shows. */
data class CrmCustomer360(
    val id: String,
    val name: String,
    val code: String?,
    val phone: String?,
    val email: String?,
    val contacts: List<CrmContact>,
    val deals: List<CrmDealRow>,
    val history: List<CrmHistoryRow>,
    val invoices: List<CrmInvoiceRow>,
    val creditLimit: BigDecimal,
    val creditHold: Boolean,
    val openReceivables: BigDecimal,
    val overdueReceivables: BigDecimal,
)

/** A possible duplicate found while typing a new lead (`GET /crm/leads/duplicates`, advisory). */
data class CrmDuplicate(val label: String, val matchedOn: List<String>)

/** What the server answered to a sign-in. */
sealed interface CrmLoginResult {
    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val displayName: String,
        val permissions: Set<String>,
    ) : CrmLoginResult

    data class NeedsMfa(val mfaToken: String) : CrmLoginResult
}

/** Reads CRM JSON defensively: a missing or null field becomes a default, never a crash. */
object CrmParsers {

    private val json = Json { ignoreUnknownKeys = true }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    fun JsonObject.dec(key: String): BigDecimal = str(key)?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: str(key)?.toDoubleOrNull()?.toInt() ?: 0

    fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> if (v is JsonNull) emptyList() else listOf(v.content)
        else -> emptyList()
    }

    private fun person(first: String?, last: String?, fallback: String? = null): String =
        listOfNotNull(first, last).joinToString(" ").trim().ifBlank { fallback ?: "—" }

    fun login(o: JsonObject): CrmLoginResult {
        o.str("mfaToken")?.let { if (o.bool("requiresMfa") || o.str("accessToken") == null) return CrmLoginResult.NeedsMfa(it) }
        val user = o.obj("user") ?: JsonObject(emptyMap())
        return CrmLoginResult.Tokens(
            accessToken = o.str("accessToken") ?: error("The server did not return a session"),
            refreshToken = o.str("refreshToken") ?: error("The server did not return a session"),
            userId = user.str("id") ?: error("The server did not return the user"),
            displayName = person(user.str("firstName"), user.str("lastName"), user.str("email")),
            permissions = o.strings("permissions").toSet(),
        )
    }

    fun planner(o: JsonObject): CrmPlanner {
        val counts = o.obj("counts") ?: JsonObject(emptyMap())
        return CrmPlanner(
            activities = o.objects("data").map {
                CrmActivity(
                    id = it.str("id").orEmpty(),
                    type = it.str("type") ?: "task",
                    title = it.str("title").orEmpty(),
                    dueAt = it.str("dueAt"),
                    state = it.str("state") ?: "planned",
                    dealId = it.str("dealId"),
                    dealName = it.str("dealName"),
                    partnerId = it.str("partnerId"),
                    partnerName = it.str("partnerName"),
                )
            },
            overdue = counts.int("overdue"),
            today = counts.int("today"),
            upcoming = counts.int("upcoming"),
        )
    }

    fun customers(o: JsonObject): List<CrmCustomerRow> = o.objects("data").map {
        CrmCustomerRow(
            id = it.str("id").orEmpty(),
            name = it.str("name").orEmpty(),
            code = it.str("code"),
            phone = it.str("phone"),
            email = it.str("email"),
            openOpportunities = it.int("openOpportunities"),
            openValue = it.dec("openValue"),
            overdueReceivables = it.dec("overdueReceivables"),
        )
    }

    fun customer(o: JsonObject): CrmCustomer360 {
        val p = o.obj("partner") ?: JsonObject(emptyMap())
        val credit = o.obj("credit") ?: JsonObject(emptyMap())
        return CrmCustomer360(
            id = p.str("id").orEmpty(),
            name = p.str("name").orEmpty(),
            code = p.str("code"),
            phone = p.str("phone"),
            email = p.str("email"),
            contacts = p.objects("contacts").map {
                CrmContact(person(it.str("firstName"), it.str("lastName")), it.str("position"), it.str("phone"),
                    it.str("email"), it.bool("isPrimary"))
            },
            deals = o.objects("opportunities").map {
                CrmDealRow(it.str("id").orEmpty(), it.str("kind") ?: "opportunity", it.str("name").orEmpty(),
                    it.str("status") ?: "open", it.str("stageName"), it.dec("amount"), it.str("currencyCode"))
            },
            history = o.objects("activities").map {
                CrmHistoryRow(it.str("type") ?: "", it.str("title").orEmpty(), it.str("status") ?: "",
                    it.str("occurredAt") ?: it.str("dueAt"))
            },
            invoices = o.objects("invoices").map {
                CrmInvoiceRow(it.str("documentNumber") ?: "—", it.str("status") ?: "", it.str("issueDate"),
                    it.dec("totalAmount"), it.dec("amountResidual"), it.str("paymentStatus"))
            },
            creditLimit = credit.dec("creditLimit"),
            creditHold = credit.bool("creditHold"),
            openReceivables = credit.dec("openReceivables"),
            overdueReceivables = credit.dec("overdueReceivables"),
        )
    }

    fun duplicates(o: JsonObject): List<CrmDuplicate> =
        o.objects("partners").map { CrmDuplicate("Customer: ${it.str("name").orEmpty()}", it.strings("matchedOn")) } +
            o.objects("deals").map {
                val kind = if (it.str("kind") == "lead") "Lead" else "Opportunity"
                // A record the user may not open comes without its name; say whose it is instead.
                val what = it.str("name") ?: it.str("companyName") ?: "(owned by ${it.str("ownerName") ?: "someone else"})"
                CrmDuplicate("$kind: $what", it.strings("matchedOn"))
            }

    /**
     * The message of a server error body (`{"message": "…"}` or `{"message": ["…", "…"]}`), or a plain fallback for
     * the status code when the body is not JSON.
     */
    fun errorMessage(body: String?, status: Int): String {
        val parsed = body?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val message: JsonElement? = (parsed as? JsonObject)?.get("message")
        val text = when (message) {
            is JsonArray -> message.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("; ")
            is JsonPrimitive -> if (message is JsonNull) null else message.content
            else -> null
        }
        return text?.takeIf { it.isNotBlank() } ?: when (status) {
            401 -> "Your CRM sign-in has expired. Sign in again."
            403 -> "You do not have permission for this."
            404 -> "Not found."
            in 500..599 -> "The server had a problem. Try again in a moment."
            else -> "Request failed ($status)."
        }
    }

    /** Money for display: grouped, the currency's usual two decimals unless whole. */
    fun money(v: BigDecimal, currency: String? = null): String {
        val scale = if (v.stripTrailingZeros().scale() <= 0) 0 else 2
        val s = String.format(Locale.US, "%,.${scale}f", v.setScale(scale, RoundingMode.HALF_UP))
        return if (currency.isNullOrBlank()) s else "$currency $s"
    }

    /** An instant (`2026-11-22T06:30:00Z`) as local `2026-11-22 09:30`; a plain date stays as it is. */
    fun shortDate(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching { LOCAL.format(Instant.parse(iso).atZone(zone)) }.getOrElse { iso.take(10) }
    }

    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
}
