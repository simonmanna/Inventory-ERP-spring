package com.poscafe.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sales captured on-device. Append-only facts (conflict rule: device wins for
 * its own sales). `serverInvoiceId`/`finalInvoiceNumber` are filled in when
 * the push response returns the mapping; until then receipts print the
 * provisional number with an OFFLINE marker.
 */
@Entity(tableName = "local_sales")
data class LocalSaleEntity(
    /** Client-minted uuid; also the op id pushed to the server. */
    @PrimaryKey val id: String,
    val occurredAt: Long,
    val actorUserId: String,
    val cashSessionLocalId: String?,
    val tableId: String?,
    val orderType: String,
    /** D<n>-NNNNNN printed on the offline receipt. */
    val provisionalNumber: String,
    val subtotal: Double,
    val taxAmount: Double,
    val total: Double,
    val tendersJson: String,
    val linesJson: String,
    /** queued | pushed | failed | discarded */
    val syncStatus: String,
    val serverInvoiceId: String?,
    val finalInvoiceNumber: String?,
    val lastError: String?,
)

/**
 * Cash sessions run on-device. The local id is client-minted and sent as
 * `clientId` in the open op so queued sales can reference the session before
 * the server ever hears about it.
 */
@Entity(tableName = "local_cash_sessions")
data class LocalCashSessionEntity(
    @PrimaryKey val id: String,
    val cashRegisterId: String,
    val actorUserId: String,
    val openedAt: Long,
    val closedAt: Long?,
    val openingFloat: Double,
    val closingCounted: Double?,
    val varianceReason: String?,
    val status: String, // open | closed
    val syncStatus: String, // queued | pushed | failed
    val serverId: String?,
    /** accountId → counted balance of each shift-tracked tender at close. */
    val closingAccountsJson: String? = null,
)

@Entity(tableName = "local_cash_movements")
data class LocalCashMovementEntity(
    @PrimaryKey val id: String,
    val sessionLocalId: String,
    val movementType: String, // pay_in | pay_out | adjustment
    val amount: Double,
    val reason: String?,
    val occurredAt: Long,
    val syncStatus: String,
)

/**
 * Parked (held) order stored locally. These are cart snapshots that the
 * cashier can recall and tender. On connected devices they also sync via
 * pos.hold / pos.recall ops so the server keeps an audit trail.
 */
@Entity(tableName = "local_holds")
data class LocalHoldEntity(
    @PrimaryKey val id: String,
    val name: String,
    val linesJson: String,
    val totalAmount: Double,
    val partnerId: String?,
    val actorUserId: String?,
    val createdAt: Long,
    val syncStatus: String, // local | queued | pushed | deleted
)

/**
 * A refund or void of a local sale, captured on-device. Mirrors the sale
 * lifecycle: the row is written first (device wins), then a `sale.refund` /
 * `sale.void` op replays against the server which reverses the invoice GL,
 * restocks, and returns drawer cash. `serverInvoiceId` is the real invoice id
 * when the sale already synced; otherwise the sale's local id (the server
 * resolves it via the batch clientId map). A partial refund names its lines
 * by position in the sale ([linesJson], `lineNumber` on the wire); each row
 * carries one stock disposition, so a refund with mixed dispositions is
 * several rows / ops.
 */
@Entity(tableName = "local_refunds")
data class LocalRefundEntity(
    /** Client-minted uuid; also the op id pushed to the server. */
    @PrimaryKey val id: String,
    /** The local sale this refunds/voids. */
    val saleLocalId: String,
    /** Server invoice id if known at creation, else the sale's local id. */
    val serverInvoiceId: String?,
    /** refund | void */
    val type: String,
    val reason: String?,
    /** Refunded amount, for local reporting/X-report drawer reconciliation. */
    val amount: Double,
    /** Manager user id that authorised a void (PIN-verified on-device). */
    val overrideById: String?,
    val cashSessionLocalId: String?,
    val occurredAt: Long,
    /** queued | pushed | failed | discarded */
    val syncStatus: String,
    val lastError: String?,
    /**
     * Refunded lines as JSON `[{"lineIndex":0,"quantity":1.0}]` (0-based index
     * into the sale's lines). Null = every line in full (void, or a refund
     * recorded before partial refunds existed).
     */
    val linesJson: String? = null,
    /** restock | waste | no_return — what happens to the returned goods. */
    val stockDisposition: String? = null,
)

/**
 * An open dine-in tab — a cart bound to a table that persists across screens
 * so rounds can be added over time. One row per table (the tableId is the PK).
 * The tab lives ONLY on the device until it settles: settling emits a single
 * `sale.checkout` (all lines, orderType=dine_in); a split emits one
 * `sale.checkout` per bill. Firing a round prints a KOT locally (no server op).
 */
@Entity(tableName = "local_tabs")
data class LocalTabEntity(
    @PrimaryKey val tableId: String,
    /** Serialized List<CartEngine.CartLine> — same shape holds use. */
    val linesJson: String,
    val guestCount: Int,
    val partnerId: String?,
    /** JSON array of lineIds already sent to the kitchen (fire delta tracking). */
    val firedLineIdsJson: String,
    val openedAt: Long,
    val updatedAt: Long,
    val actorUserId: String?,
)

/**
 * The push queue. One row per op, strictly ordered by deviceSeq. Never
 * deleted on failure — failed ops flip to `failed` and surface in the sync
 * screen (mirror of the server's dead-letter philosophy).
 */
@Entity(tableName = "op_queue")
data class OpQueueEntity(
    @PrimaryKey val opId: String,
    val deviceSeq: Long,
    val type: String,
    val actorUserId: String,
    val occurredAt: Long,
    val payloadJson: String,
    val status: String, // queued | pushed | failed | discarded | superseded
    val attempts: Int,
    val lastError: String?,
    /**
     * Legacy (before protocol 2 / Room v15): the key a rejected op was re-sent under. No longer set — an opId is
     * never re-minted (ADR-S083 S1) — but a queued op re-keyed by an older build keeps going out under it, because
     * that is the key the server knows.
     */
    val wireOpId: String? = null,
    /**
     * HTTP status of a definitive rejection (4xx: the server committed nothing).
     * Its key stays locked server-side, so a retry must go out under a new key.
     * Null for transient/ambiguous failures, which must keep their key.
     */
    val rejectedStatus: Int? = null,
    /** ADR-S083 S1: the rejected op this one corrects (a person resubmitted it); sent as `supersedesOpId`. */
    val supersedesOpId: String? = null,
    /**
     * The local row (sale, refund, expense, …) this op reports on, when it differs from [opId]: a correction keeps
     * the row id of the op it supersedes, so its result lands on the same sale. Null = [opId].
     */
    val rowId: String? = null,
    /** Server failure code of the last answer (BUSINESS_RULE, SERVER_ERROR, DEVICE_TOKEN_EXPIRED, …). */
    val failureCode: String? = null,
    /** The server said the same opId may run again (5xx, a dependency, an op still in flight). */
    val retryable: Boolean = false,
    /** Epoch ms before which a retryable op is not re-sent (backoff); 0 = now. */
    val nextAttemptAt: Long = 0,
)
