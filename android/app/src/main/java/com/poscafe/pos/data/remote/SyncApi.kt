package com.poscafe.pos.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

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
)

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
)

@Serializable
data class PushResponse(
    val results: List<PushOpResult>,
    val lastPushSeq: Long,
    val serverTime: String,
)
