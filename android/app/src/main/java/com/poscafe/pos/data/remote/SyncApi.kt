package com.poscafe.pos.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/** ADR-S083 S8: the protocol this app speaks; sent on every request. */
const val SYNC_PROTOCOL_HEADER = "X-Sync-Protocol"
const val SYNC_PROTOCOL = "2.0"

/**
 * The device side of the P1 sync protocol (Spring `com.inventoryerp.sync`, `SyncController`).
 * Transport auth = X-Device-Token (added by an OkHttp interceptor).
 */
interface SyncApi {

    @GET("sync/pull")
    suspend fun pull(
        @Query("cursor") cursor: String?,
        @Query("scopes") scopes: String? = null,
    ): PullResponse

    @POST("sync/push")
    suspend fun push(
        @Header("Idempotency-Key") batchId: String,
        @Body body: PushRequest,
    ): PushResponse
}

@Serializable
data class PullResponse(
    /** scope name → raw rows; parsed per-scope by SyncRepository. */
    val data: Map<String, List<JsonObject>>,
    val cursor: String,
    val serverTime: String,
    /**
     * The installation's licence, as of this pull (Spring `LicenseSyncView`).
     *
     * Carried here rather than on a route of its own so a till learns its enforcement mode on the
     * call it already makes — on a connection that may be about to disappear, a second round trip
     * is a second chance to fail. Nullable because an older server does not send it, and a terminal
     * must keep working against one that does not.
     */
    val license: LicenseView? = null,
    /** Phase I6: organisation currency; its decimal places are the server's tax rounding scale. */
    val currency: CurrencyView? = null,
    // ADR-S083 (protocol 2, O6). All optional: an older server sends none of them.
    val protocolVersion: String? = null,
    val serverId: String? = null,
    /** The rotated device token (protocol 2). Stored before the rows are applied; the old one stays valid 24 h. */
    val deviceToken: String? = null,
    val deviceTokenExpiresAt: String? = null,
    val deviceBranchId: String? = null,
    val offlinePolicy: OfflinePolicyView? = null,
)

/** ADR-S083 C4: the user offline-session rules the device enforces (org setting, a device policy may only lower it). */
@Serializable
data class OfflinePolicyView(
    val offlineWindowHours: Int? = null,
    val idleTimeoutMinutes: Int? = null,
    val pinMaxAttempts: Int? = null,
    val pinLockoutMinutes: Int? = null,
)

@Serializable
data class CurrencyView(val code: String, val decimalPlaces: Int? = null)

/**
 * What a till is told about the licence. Deliberately small: it needs to know whether it may take a
 * sale, how long it has, and what to show. It is not told the customer, the plan or the fingerprint
 * — a device token is a weaker credential than a user session.
 */
@Serializable
data class LicenseView(
    /** UNLICENSED, TRIAL, ACTIVE, GRACE, READ_ONLY or REVOKED. */
    val mode: String,
    val reason: String? = null,
    /** The only thing the till branches on: may it start a new sale? */
    val writable: Boolean = true,
    val expiresAt: String? = null,
    val graceUntil: String? = null,
    val daysRemaining: Long? = null,
    /** Whether POS terminals are included in the licence at all. */
    val androidPos: Boolean = true,
    /** The server's own wording for why writes are refused, or null when they are not. */
    val message: String? = null,
)

@Serializable
data class PushOp(
    val opId: String,
    val deviceSeq: Long,
    val type: String,
    val actorUserId: String,
    val occurredAt: String,
    val payload: JsonElement,
    /** ADR-S083 S1: a person's correction of a rejected op names it; omitted otherwise. */
    val supersedesOpId: String? = null,
)

@Serializable
data class PushRequest(
    val deviceId: String,
    val ops: List<PushOp>,
)

@Serializable
data class PushOpResult(
    val opId: String,
    val status: String, // applied | replayed | failed
    val httpStatus: Int,
    val error: String? = null,
    val mapping: Map<String, String>? = null,
    val finalNumbers: Map<String, String>? = null,
    /** ADR-S083 S1 failure code (BUSINESS_RULE, SERVER_ERROR, DEVICE_REVOKED, …) and whether the same opId may retry. */
    val code: String? = null,
    val retryable: Boolean? = null,
)

@Serializable
data class PushResponse(
    val results: List<PushOpResult>,
    val lastPushSeq: Long,
    val serverTime: String,
)
