package com.inventoryerp.sync.application;

import com.inventoryerp.inventory.application.StockDocService;
import com.inventoryerp.inventory.application.StockDocService.DocInput;
import com.inventoryerp.inventory.application.StockDocService.DocLine;
import com.inventoryerp.inventory.application.StockDocService.ReceiveLine;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.idempotency.IdempotencyService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.procurement.application.GoodsReceiptsService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-S083 O4 / S4 — inventory and warehouse work recorded on an offline device.
 *
 * <p>Every op reuses the online service ({@link StockDocService}, {@link GoodsReceiptsService}); nothing here posts
 * stock by itself. The S4 rules on top:
 * <ul>
 *   <li>A physical fact is kept. When the server refuses the posting step (negative stock under a blocking policy,
 *   an over-receipt, a closed order…), the document stays and an {@code offline_held_posting} exception tells a
 *   supervisor why — the op is applied, not dead-lettered.</li>
 *   <li>Stock that goes below zero through an offline issue raises {@code offline_negative_stock}.</li>
 *   <li>A transfer receipt whose dispatch the server does not have yet waits as {@code offline_awaiting_dispatch}
 *   and is applied when the dispatch arrives (here, or by {@link SyncStockJob} for a dispatch made elsewhere).</li>
 *   <li>Documents are stamped {@code recordedOffline} + {@code occurredAt}.</li>
 * </ul>
 * Discrepancies are {@code InventoryException} rows: they appear in the Posting Monitor, are resolved there, and an
 * open one in a period blocks its close.
 */
@Service
public class SyncStockService {

    private static final Logger log = LoggerFactory.getLogger(SyncStockService.class);

    public static final String NEGATIVE = "offline_negative_stock";
    public static final String HELD = "offline_held_posting";
    public static final String AWAITING = "offline_awaiting_dispatch";

    /** Transfer statuses before dispatch: a receipt waits. */
    private static final Set<String> NOT_DISPATCHED = Set.of("draft", "pending", "approved");
    /** Transfer statuses that accept a receipt. */
    private static final Set<String> RECEIVABLE = Set.of("in_transit", "partially_received");

    private final JdbcClient jdbc;
    private final StockDocService docs;
    private final GoodsReceiptsService grns;
    private final TransactionTemplate tx;
    private IdempotencyService idempotency;

    @org.springframework.beans.factory.annotation.Autowired
    void setIdempotency(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    public SyncStockService(PosDb db, StockDocService docs, GoodsReceiptsService grns,
            PlatformTransactionManager txm) {
        this.jdbc = db.jdbc();
        this.docs = docs;
        this.grns = grns;
        this.tx = new TransactionTemplate(txm);
    }

    /** Who and where an op came from (for stamps and exception context). */
    public record OpContext(String opId, String opType, String deviceId, String branchId, String actorUserId,
            String occurredAt) {}

    private static String org() {
        return TenantContext.organizationId();
    }

    // ---------------------------------------------------------------------------------------------- transfer.dispatch

    /**
     * {@code transfer.dispatch}: dispatch an approved transit transfer ({@code transferId}), or create one from
     * {@code fromLocationId} / {@code toLocationId} / {@code items} and approve + dispatch it. Refused approval or
     * dispatch → held.
     */
    public Map<String, Object> dispatch(OpContext c, Map<String, Object> p) {
        requirePermission(Permissions.INVENTORY__MOVE);
        String transferId = str(p.get("transferId"));
        if (transferId == null) {
            transferId = createTransitTransfer(c, p);
        } else if (transfer(transferId) == null) {
            throw ApiException.notFound("Transfer not found");
        }
        String held = null;
        Map<String, Object> t = transfer(transferId);
        if (NOT_DISPATCHED.contains(str(t.get("status")))) {
            if (!"approved".equals(str(t.get("status")))) {
                held = holdOnRefusal(c, t, () -> docs.approveTransfer(t.get("id").toString()));
            }
            if (held == null) {
                String id = transferId;
                held = holdOnRefusal(c, t, () -> docs.dispatchTransfer(id));
            }
        } else if (!RECEIVABLE.contains(str(t.get("status")))) {
            throw ApiException.badRequest("Transfer " + t.get("transferCode") + " is " + t.get("status")
                    + "; it cannot be dispatched");
        }
        stamp("StockTransfer", transferId, c);
        Map<String, Object> after = transfer(transferId);
        boolean dispatched = RECEIVABLE.contains(str(after.get("status")))
                || "completed".equals(str(after.get("status")));
        if (dispatched) {
            for (Map<String, Object> it : transferItems(transferId)) {
                flagNegative(c, str(after.get("fromLocId")), str(it.get("productId")), str(it.get("variantId")),
                        "stock_transfer", transferId, str(after.get("transferCode")));
            }
            applyAwaiting(transferId);
        }
        String clientId = str(p.get("clientId"));
        Map<String, Object> mapping = new LinkedHashMap<>();
        if (clientId != null) {
            mapping.put(clientId, transferId);
        }
        mapping.put("transferId", transferId);
        mapping.put("transferCode", str(after.get("transferCode")));
        return Obj.of("id", transferId, "mapping", mapping, "outcome", outcome(dispatched ? "dispatched" : "held", held));
    }

    private String createTransitTransfer(OpContext c, Map<String, Object> p) {
        List<DocLine> items = new ArrayList<>();
        for (Object o : PosSql.list(p.get("items"))) {
            Map<String, Object> it = PosSql.map(o);
            items.add(new DocLine(str(it.get("productId")), str(it.get("variantId")), str(it.get("unit")),
                    str(it.get("batchNumber")), null, str(it.get("distStrategy")), null, null,
                    PosSql.dec(it.get("quantity"))));
        }
        if (str(p.get("fromLocationId")) == null || str(p.get("toLocationId")) == null || items.isEmpty()) {
            throw ApiException.badRequest("transfer.dispatch needs transferId, or fromLocationId, toLocationId and items");
        }
        Map<String, Object> created = docs.createTransfer(new DocInput(null, str(p.get("fromLocationId")),
                str(p.get("toLocationId")), "transit", c.actorUserId(), null, null, null, str(p.get("notes")), items));
        return String.valueOf(created.get("id"));
    }

    // ----------------------------------------------------------------------------------------------- transfer.receive

    /**
     * {@code transfer.receive}: receive lines ({@code itemId}, {@code received}, {@code damaged}, {@code short}) of a
     * transit transfer. Not dispatched on the server yet → waits; refused → held.
     */
    public Map<String, Object> receive(OpContext c, Map<String, Object> p) {
        requirePermission(Permissions.INVENTORY__MOVE);
        String transferId = str(p.get("transferId"));
        if (transferId == null) {
            throw ApiException.badRequest("transfer.receive requires transferId");
        }
        Map<String, Object> t = transfer(transferId);
        if (t == null) {
            throw ApiException.notFound("Transfer not found");
        }
        receiveLines(p); // validate the shape now, not when the dispatch arrives
        String status = str(t.get("status"));
        if (NOT_DISPATCHED.contains(status)) {
            String id = exception(AWAITING, str(t.get("toLocId")), null, null,
                    "Receipt of " + t.get("transferCode") + " waits for its dispatch to sync",
                    "Received on a device before the server has the dispatch", context(c, "stock_transfer", transferId,
                            str(t.get("transferCode")), Obj.of("transferId", transferId, "receipt", p)));
            return Obj.of("id", transferId, "outcome", outcome("awaiting_dispatch", null));
        }
        String held = holdOnRefusal(c, t, () -> docs.receiveTransfer(transferId, receiveLines(p), str(p.get("notes"))));
        stamp("StockTransfer", transferId, c);
        return Obj.of("id", transferId, "outcome", outcome(held == null ? "received" : "held", held));
    }

    private static List<ReceiveLine> receiveLines(Map<String, Object> p) {
        List<ReceiveLine> out = new ArrayList<>();
        for (Object o : PosSql.list(p.get("lines"))) {
            Map<String, Object> l = PosSql.map(o);
            if (str(l.get("itemId")) == null) {
                throw ApiException.badRequest("Every receipt line needs itemId");
            }
            out.add(new ReceiveLine(str(l.get("itemId")), PosSql.dec(l.get("received")), PosSql.dec(l.get("damaged")),
                    PosSql.dec(l.get("short"))));
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("transfer.receive requires lines");
        }
        return out;
    }

    /**
     * Apply the receipts waiting on {@code transferId} once it is in transit. Each runs as the person who recorded
     * it; a refusal turns it into a held posting.
     *
     * <p>R1 (P1-13, owner-approved 2026-11-23): exactly once, crash-safe. Per waiting row, ONE transaction claims the
     * row ({@code FOR UPDATE SKIP LOCKED}, still {@code open}), receives under the idempotency key
     * {@code offline-receipt:<exceptionId>} and marks the row resolved — all commit together, so a crash rolls back
     * the claim, the receipt and the resolve at once (no "applying" state is ever stored), and a concurrent runner
     * (the sync job and a dispatch sync) skips a row another one holds. A refusal rolls the transaction back and a new
     * transaction marks the row held with the reason.
     */
    public int applyAwaiting(String transferId) {
        Map<String, Object> t = transfer(transferId);
        if (t == null || !RECEIVABLE.contains(str(t.get("status")))) {
            return 0;
        }
        String org = org();
        int applied = 0;
        for (Map<String, Object> row : SqlRows.list(jdbc.sql("SELECT id, payload FROM \"InventoryException\" WHERE"
                + " \"organizationId\" = ? AND kind = ? AND status = 'open' AND payload->>'transferId' = ?"
                + " ORDER BY \"createdAt\" ASC").params(org, AWAITING, transferId))) {
            String exceptionId = String.valueOf(row.get("id"));
            Map<String, Object> ctx = json(row.get("payload"));
            Map<String, Object> receipt = PosSql.map(ctx.get("receipt"));
            String actor = str(ctx.get("actorUserId"));
            try {
                Boolean done = TenantContext.run(TenantContext.TenantStore.user(org, actor),
                        () -> tx.execute(st -> applyOne(org, transferId, exceptionId, receipt)));
                if (Boolean.TRUE.equals(done)) {
                    applied++;
                }
            } catch (ApiException e) {
                tx.executeWithoutResult(st -> jdbc.sql("UPDATE \"InventoryException\" SET kind = ?, reason = ?"
                                + " WHERE id = ? AND \"organizationId\" = ? AND status = 'open' AND kind = ?")
                        .params(HELD, "Receipt refused after the dispatch synced: " + e.getMessage(), exceptionId, org,
                                AWAITING)
                        .update());
            }
        }
        return applied;
    }

    /** Test hook (R1): runs after a waiting receipt is posted, before its row is resolved (crash simulation). */
    static volatile Runnable afterReceiptHook;

    /** One waiting receipt in the caller's transaction; false when another runner holds or already applied it. */
    private boolean applyOne(String org, String transferId, String exceptionId, Map<String, Object> receipt) {
        List<String> claimed = jdbc.sql("SELECT id FROM \"InventoryException\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND kind = ? AND status = 'open' FOR UPDATE SKIP LOCKED")
                .params(exceptionId, org, AWAITING)
                .query(String.class).list();
        if (claimed.isEmpty()) {
            return false;
        }
        String key = "offline-receipt:" + exceptionId;
        idempotency.executeWithKey(key, idempotency.hashPayload(Map.of("exceptionId", exceptionId)), "SYNC",
                "offline-receipt", null, () -> {
                    docs.receiveTransfer(transferId, receiveLines(receipt), str(receipt.get("notes")));
                    return new IdempotencyService.Result(false, 200, Map.of("exceptionId", exceptionId));
                });
        Runnable hook = afterReceiptHook;
        if (hook != null) {
            hook.run();
        }
        resolve(exceptionId, "Applied when the dispatch synced");
        return true;
    }

    /**
     * R1 (P1-3): an offline refund / void applied while its sale's stock posting could not complete — the refund
     * stands with {@code stockDisposition = hold} and its goods wait here ({@code offline_held_posting}) for a
     * supervisor to restock them once the sale's issue is resolved. Its own transaction (it must survive the op).
     */
    public String holdRefundRestock(OpContext c, String invoiceId, String refundId, List<Map<String, Object>> lines) {
        Map<String, Object> inv = SqlRows.one(jdbc.sql("SELECT \"invoiceNumber\" FROM \"Invoice\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(invoiceId, org()));
        String number = inv == null ? invoiceId : str(inv.get("invoiceNumber"));
        String reason = "The sale's stock posting is not done, so the returned goods were not restocked";
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("invoiceId", invoiceId);
        extra.put("refundId", refundId);
        extra.put("lines", lines);
        extra.put("stockDisposition", "hold");
        String id = exception(HELD, null, null, null, "Refund of " + number + " held: restock after the sale's stock"
                + " posting is resolved", reason, context(c, "pos_refund", refundId, number, extra));
        log.info("offline refund {} of {} held: {}", refundId, number, reason);
        return id;
    }

    // ---------------------------------------------------------------------------------------------------- stock.adjust

    /**
     * {@code stock.adjust}: the device sends what it found ({@code qtyChange}, signed). The adjustment is created
     * against today's on-hand (counted = on-hand + change) and waits for approval as any adjustment does — the
     * approval limits and maker/checker rules stay the server's.
     */
    public Map<String, Object> adjust(OpContext c, Map<String, Object> p) {
        requirePermission(Permissions.INVENTORY__MOVE);
        String locationId = str(p.get("locationId"));
        List<DocLine> items = new ArrayList<>();
        for (Object o : PosSql.list(p.get("lines"))) {
            Map<String, Object> l = PosSql.map(o);
            BigDecimal change = PosSql.dec(l.get("qtyChange"));
            if (change == null || change.signum() == 0) {
                throw ApiException.badRequest("Every adjustment line needs a non-zero qtyChange");
            }
            BigDecimal onHand = onHand(locationId, str(l.get("productId")), str(l.get("variantId")));
            items.add(new DocLine(str(l.get("productId")), str(l.get("variantId")), str(l.get("unit")),
                    str(l.get("batchNumber")), null, null, null, onHand.add(change), null));
        }
        if (locationId == null || items.isEmpty()) {
            throw ApiException.badRequest("stock.adjust requires locationId and lines");
        }
        Map<String, Object> doc = docs.createAdjustment(new DocInput(locationId, null, null, null, c.actorUserId(), null,
                null, str(p.get("reason")), str(p.get("notes")), items), false);
        String id = String.valueOf(doc.get("id"));
        stamp("StockAdjustment", id, c);
        String clientId = str(p.get("clientId"));
        Map<String, Object> mapping = new LinkedHashMap<>();
        if (clientId != null) {
            mapping.put(clientId, id);
        }
        mapping.put("adjustmentId", id);
        mapping.put("adjCode", str(doc.get("adjCode")));
        return Obj.of("id", id, "mapping", mapping, "outcome", outcome("pending_approval", null));
    }

    // ----------------------------------------------------------------------------------------------------- grn.receive

    /**
     * {@code grn.receive}: a goods receipt against a purchase order, created as a draft and posted. Without
     * {@code goods_receipt:post}, or when posting is refused, the draft stays and is held.
     */
    public Map<String, Object> receiveGoods(OpContext c, Map<String, Object> p) {
        requirePermission(Permissions.PROCUREMENT__GOODS_RECEIPT__CREATE);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("purchaseOrderId", p.get("purchaseOrderId"));
        input.put("warehouseId", p.get("warehouseId") != null ? p.get("warehouseId") : p.get("locationId"));
        input.put("branchId", p.get("branchId") != null ? p.get("branchId") : c.branchId());
        input.put("receivedAt", c.occurredAt());
        input.put("notes", p.get("notes"));
        input.put("lines", p.get("lines"));
        Map<String, Object> draft = grns.createDraft(input);
        String id = String.valueOf(draft.get("id"));
        String number = str(draft.get("receiptNumber"));
        String held;
        if (!TenantContext.permissions().contains(Permissions.PROCUREMENT__GOODS_RECEIPT__POST)) {
            held = "Recorded offline by someone without goods_receipt:post; post the draft receipt";
            exception(HELD, str(input.get("warehouseId")), null, null, "Goods receipt " + number + " not posted", held,
                    context(c, "goods_receipt", id, number, Map.of()));
        } else {
            held = hold(c, "goods_receipt", id, number, str(input.get("warehouseId")), () -> grns.post(id));
        }
        stamp("GoodsReceiptNote", id, c);
        String clientId = str(p.get("clientId"));
        Map<String, Object> mapping = new LinkedHashMap<>();
        if (clientId != null) {
            mapping.put(clientId, id);
        }
        mapping.put("goodsReceiptId", id);
        mapping.put("receiptNumber", number);
        return Obj.of("id", id, "mapping", mapping, "outcome", outcome(held == null ? "posted" : "held", held));
    }

    // ------------------------------------------------------------------------------------------------------- stock.out

    /** After an offline issue ({@code stock.out}): flag every line whose location went below zero. */
    public void flagNegativeLines(OpContext c, String locationId, List<Map<String, Object>> lines, String docType,
            String docCode) {
        for (Map<String, Object> l : lines) {
            flagNegative(c, locationId, str(l.get("productId")), str(l.get("variantId")), docType, null, docCode);
        }
    }

    // ------------------------------------------------------------------------------------------------------- counting

    /**
     * S4 counts: what moved at the location after the device counted. The count is compared with today's on-hand,
     * so the counted figure is carried forward by those movements — the variance is counted − on-hand at
     * {@code occurredAt}.
     */
    public BigDecimal movedSince(String locationId, String productId, String variantId, String occurredAt) {
        if (occurredAt == null || locationId == null || productId == null) {
            return BigDecimal.ZERO;
        }
        Instant at = EntityPatch.toInstant(occurredAt);
        return jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                        + " AND \"locationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ?"
                        + " AND \"createdAt\" > ?")
                .params(org(), locationId, productId, variantId, SqlRows.ts(at)).query(BigDecimal.class).single();
    }

    // -------------------------------------------------------------------------------------------------------- report

    /**
     * The inventory discrepancy report: every offline-flagged or held movement, newest first, filterable by
     * branch, device, location, kind and status.
     */
    public List<Map<String, Object>> discrepancies(String branchId, String deviceId, String locationId, String kind,
            String status) {
        StringBuilder sql = new StringBuilder("SELECT e.id, e.kind, e.status, e.description, e.reason, e.quantity,"
                + " e.\"locationId\", l.name AS \"locationName\", e.\"productId\", p.name AS \"productName\", e.payload,"
                + " e.\"createdAt\", e.\"resolvedAt\", e.\"resolvedById\", e.\"resolutionNote\" FROM \"InventoryException\" e"
                + " LEFT JOIN \"InventoryLocation\" l ON l.id = e.\"locationId\" LEFT JOIN \"Product\" p ON p.id ="
                + " e.\"productId\" WHERE e.\"organizationId\" = ? AND e.kind LIKE 'offline_%'");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (branchId != null) {
            sql.append(" AND e.payload->>'branchId' = ?");
            params.add(branchId);
        }
        if (deviceId != null) {
            sql.append(" AND e.payload->>'deviceId' = ?");
            params.add(deviceId);
        }
        if (locationId != null) {
            sql.append(" AND e.\"locationId\" = ?");
            params.add(locationId);
        }
        if (kind != null) {
            sql.append(" AND e.kind = ?");
            params.add(kind);
        }
        if (status != null) {
            sql.append(" AND e.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY e.\"createdAt\" DESC LIMIT 500");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(sql.toString()).params(params))) {
            Object payload = r.get("payload");
            Map<String, Object> ctx = json(payload);
            r.remove("payload");
            for (String k : List.of("opId", "opType", "deviceId", "branchId", "actorUserId", "occurredAt",
                    "documentType", "documentId", "documentCode", "onHandAfter")) {
                r.put(k, ctx.get(k));
            }
            r.put("quantity", PosSql.js(r.get("quantity")));
            out.add(r);
        }
        return out;
    }

    // -------------------------------------------------------------------------------------------------------- helpers

    private interface Step {
        Object run();
    }

    /** Run a posting step; a business refusal (400 / 409 / 422) becomes a held-posting exception (its reason). */
    private String holdOnRefusal(OpContext c, Map<String, Object> transfer, Step step) {
        return hold(c, "stock_transfer", str(transfer.get("id")), str(transfer.get("transferCode")),
                str(transfer.get("fromLocId")), step);
    }

    private String hold(OpContext c, String docType, String docId, String docCode, String locationId, Step step) {
        try {
            step.run();
            return null;
        } catch (ApiException e) {
            HttpStatus s = e.status();
            if (s != HttpStatus.BAD_REQUEST && s != HttpStatus.CONFLICT && s != HttpStatus.UNPROCESSABLE_ENTITY) {
                throw e;
            }
            String reason = e.getMessage();
            exception(HELD, locationId, null, null, (docCode != null ? docCode : docType) + " held: " + reason, reason,
                    context(c, docType, docId, docCode, Map.of()));
            log.info("offline {} {} held: {}", docType, docId, reason);
            return reason;
        }
    }

    private void flagNegative(OpContext c, String locationId, String productId, String variantId, String docType,
            String docId, String docCode) {
        if (locationId == null || productId == null) {
            return;
        }
        BigDecimal q = onHand(locationId, productId, variantId);
        if (q.signum() >= 0) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("onHandAfter", PosSql.js(q));
        extra.put("variantId", variantId);
        exception(NEGATIVE, locationId, productId, q, "On hand below zero after an offline movement",
                "Stock went to " + q.stripTrailingZeros().toPlainString() + " after work recorded offline; count or"
                        + " adjust the location", context(c, docType, docId, docCode, extra));
    }

    private BigDecimal onHand(String locationId, String productId, String variantId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                        + " \"locationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ?")
                .params(org(), locationId, productId, variantId).query(BigDecimal.class).single();
    }

    private static Map<String, Object> context(OpContext c, String docType, String docId, String docCode,
            Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opId", c.opId());
        m.put("opType", c.opType());
        m.put("deviceId", c.deviceId());
        m.put("branchId", c.branchId());
        m.put("actorUserId", c.actorUserId());
        m.put("occurredAt", c.occurredAt());
        m.put("documentType", docType);
        m.put("documentId", docId);
        m.put("documentCode", docCode);
        m.putAll(extra);
        return m;
    }

    /** One exception row in its own transaction (it must survive whatever the op does next). */
    private String exception(String kind, String locationId, String productId, BigDecimal quantity, String description,
            String reason, Map<String, Object> context) {
        String id = UUID.randomUUID().toString();
        String org = org();
        tx.executeWithoutResult(s -> jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", \"locationId\","
                        + " \"productId\", description, quantity, kind, reason, payload, status, \"createdAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'open', CURRENT_TIMESTAMP)")
                .params(id, org, locationId, productId, description, quantity == null ? BigDecimal.ZERO : quantity, kind,
                        reason, EntityJson.toJson(context))
                .update());
        return id;
    }

    private void resolve(String id, String note) {
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved', \"resolvedAt\" = CURRENT_TIMESTAMP,"
                + " \"resolutionNote\" = ? WHERE id = ? AND \"organizationId\" = ?").params(note, id, org()).update();
    }

    private void stamp(String table, String id, OpContext c) {
        Instant at = c.occurredAt() == null ? null : EntityPatch.toInstant(c.occurredAt());
        tx.executeWithoutResult(s -> jdbc.sql("UPDATE \"" + table + "\" SET \"recordedOffline\" = true, \"occurredAt\" = ?"
                + " WHERE id = ? AND \"organizationId\" = ?").params(at == null ? null : SqlRows.ts(at), id, org()).update());
    }

    private Map<String, Object> transfer(String id) {
        return SqlRows.one(jdbc.sql("SELECT id, \"transferCode\", status::text AS status, mode, \"fromLocId\", \"toLocId\""
                + " FROM \"StockTransfer\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()));
    }

    private List<Map<String, Object>> transferItems(String id) {
        return SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantId\" FROM \"StockTransferItem\" WHERE \"transferId\" = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
    }

    private static void requirePermission(String permission) {
        if (!TenantContext.permissions().contains(permission)) {
            throw ApiException.forbidden("Missing permission " + permission);
        }
    }

    /** The {@code outcome} member of the push result: a state and, when held, why. */
    private static Map<String, Object> outcome(String state, String reason) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("state", state);
        if (reason != null) {
            o.put("reason", reason);
        }
        return o;
    }

    /** A jsonb column (SqlRows gives a JSON tree) as a map; never null. */
    private static Map<String, Object> json(Object v) {
        Map<String, Object> m = PosSql.map(PosSql.java(v));
        return m == null ? Map.of() : m;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v instanceof String x ? x : PosSql.jsString(v);
        return s.isEmpty() ? null : s;
    }
}
