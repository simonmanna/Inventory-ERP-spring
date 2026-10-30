package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purchase-order change requests and revisions (ADR-PROCUREMENT-001 wave 5). A buyer asks to change an open order
 * (line quantity, line price, expected delivery); the change applies at once when it does not raise the total beyond
 * {@code change.reapprovalAbovePct}, otherwise a second person with {@code po_change:approve} decides. Applying a change
 * writes a revision snapshot (revision 0 = the order before its first change), re-states budget commitments, returns
 * or takes quantity on the request lines it was sourced from, and asks the supplier to confirm again.
 *
 * <p>Rules: quantity cannot go below what was received plus short-closed; a line with receipts keeps its price (raise
 * a claim or debit note instead); lines released from a purchase agreement change on the agreement, not here; one open
 * change per order.
 */
@Service
public class PoChangeService {

    /** One line change; {@code null} fields stay as they are. */
    public record LineChange(String lineId, BigDecimal quantity, BigDecimal unitPrice) {}

    /** Change body. */
    public record ChangeInput(String reason, String expectedDeliveryDate, List<LineChange> lines) {}

    private static final List<String> OPEN = List.of("active", "partially_received");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final PurchaseCommitments commitments;
    private final ProcurementPolicyService policies;
    private final TransactionTemplate tx;

    public PoChangeService(JdbcClient jdbc, AuditService audit, EventBus events, PurchaseCommitments commitments,
            ProcurementPolicyService policies, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.commitments = commitments;
        this.policies = policies;
        this.tx = new TransactionTemplate(txm);
    }

    /** Raise a change; applied immediately when no second approval is needed. */
    public Map<String, Object> request(String poId, ChangeInput in) {
        if (in.reason() == null || in.reason().isBlank()) {
            throw ApiException.badRequest("A reason is required to change an order");
        }
        boolean noLines = in.lines() == null || in.lines().isEmpty();
        if (noLines && (in.expectedDeliveryDate() == null || in.expectedDeliveryDate().isBlank())) {
            throw ApiException.badRequest("Nothing to change");
        }
        String orgId = org();
        String id = UUID.randomUUID().toString();
        tx.executeWithoutResult(st -> {
            Map<String, Object> po = lockOpen(poId);
            Map<String, Map<String, Object>> lines = lines(poId);
            BigDecimal oldTotal = Money.dec(po.get("totalAmount"));
            BigDecimal newTotal = validateAndTotal(po, lines, in);
            BigDecimal pct = Money.dec(policies.number(ProcurementPolicyRegistry.CHANGE_REAPPROVAL_ABOVE,
                    ProcurementPolicyService.PolicyContext.branch((String) po.get("branchId"))));
            BigDecimal limit = oldTotal.add(Money.div(oldTotal.multiply(pct), BigDecimal.valueOf(100)));
            boolean needsApproval = newTotal.compareTo(oldTotal) > 0 && (pct.signum() == 0 || newTotal.compareTo(limit) > 0);
            int number = jdbc.sql("SELECT COALESCE(MAX(\"changeNumber\"), 0) + 1 FROM \"PoChangeRequest\" WHERE"
                    + " \"purchaseOrderId\" = ?").param(poId).query(Integer.class).single();
            try {
                jdbc.sql("INSERT INTO \"PoChangeRequest\" (id, \"organizationId\", \"purchaseOrderId\", \"changeNumber\","
                                + " status, reason, changes, \"oldTotal\", \"newTotal\", \"needsApproval\", \"requestedBy\","
                                + " \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, 'pending', ?, ?::jsonb, ?, ?, ?, ?, "
                                + NOW + ", " + NOW + ")")
                        .params(id, orgId, poId, number, in.reason().trim(), EntityJson.toJson(changesJson(in)), oldTotal,
                                newTotal, needsApproval, user())
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw ApiException.conflict("This order already has an open change request — decide it first");
            }
            audit.recordInTx(AuditInput.of("PoChangeRequest", id, "create", null, Obj.of("purchaseOrderId", poId,
                    "oldTotal", JsNumbers.dec(oldTotal), "newTotal", JsNumbers.dec(newTotal), "needsApproval",
                    needsApproval)));
            if (!needsApproval) {
                apply(id);
            }
        });
        return get(id);
    }

    /** Approve and apply a pending change (not by the person who asked). */
    public Map<String, Object> approve(String changeId, String note) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> cr = lockPending(changeId);
            if (user() != null && user().equals(cr.get("requestedBy"))) {
                throw ApiException.forbidden("You cannot approve a change you requested — another approver must decide");
            }
            jdbc.sql("UPDATE \"PoChangeRequest\" SET status = 'approved', \"decidedBy\" = ?, \"decidedAt\" = " + NOW
                    + ", \"decisionNote\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?").params(user(), note, changeId)
                    .update();
            apply(changeId);
        });
        return get(changeId);
    }

    /** Reject a pending change. */
    public Map<String, Object> reject(String changeId, String note) {
        if (note == null || note.isBlank()) {
            throw ApiException.badRequest("A reason is required to reject a change");
        }
        tx.executeWithoutResult(st -> {
            lockPending(changeId);
            jdbc.sql("UPDATE \"PoChangeRequest\" SET status = 'rejected', \"decidedBy\" = ?, \"decidedAt\" = " + NOW
                    + ", \"decisionNote\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(user(), note.trim(), changeId).update();
            audit.recordInTx(AuditInput.of("PoChangeRequest", changeId, "reject", null, Obj.of("note", note.trim())));
        });
        return get(changeId);
    }

    /** Withdraw a pending change (the requester). */
    public Map<String, Object> cancel(String changeId) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> cr = lockPending(changeId);
            if (user() != null && !user().equals(cr.get("requestedBy"))) {
                throw ApiException.forbidden("Only the person who asked for the change can withdraw it");
            }
            jdbc.sql("UPDATE \"PoChangeRequest\" SET status = 'cancelled', \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .param(changeId).update();
        });
        return get(changeId);
    }

    /** Change requests and revisions of an order, newest first. */
    public Map<String, Object> history(String poId) {
        requireOrder(poId);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM \"PoChangeRequest\" WHERE \"purchaseOrderId\" = ?"
                + " AND \"organizationId\" = ? ORDER BY \"changeNumber\" DESC").params(poId, org()))) {
            changes.add(wire(r));
        }
        List<Map<String, Object>> revisions = SqlRows.list(jdbc.sql("SELECT id, revision, \"changeRequestId\","
                + " \"createdBy\", \"createdAt\", snapshot FROM \"PoRevision\" WHERE \"purchaseOrderId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY revision DESC").params(poId, org()));
        Integer revision = jdbc.sql("SELECT revision FROM \"PurchaseOrder\" WHERE id = ?").param(poId)
                .query(Integer.class).single();
        return Obj.of("purchaseOrderId", poId, "revision", revision, "changes", changes, "revisions", revisions);
    }

    /** One change request. */
    public Map<String, Object> get(String changeId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"PoChangeRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(changeId, org()));
        if (r == null) {
            throw ApiException.notFound("Change request not found");
        }
        return wire(r);
    }

    /** Pending changes waiting for a decision (approver inbox). */
    public List<Map<String, Object>> pending() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT c.*, o.\"orderNumber\", p.name AS \"partnerName\" FROM"
                + " \"PoChangeRequest\" c JOIN \"PurchaseOrder\" o ON o.id = c.\"purchaseOrderId\" LEFT JOIN \"Partner\" p"
                + " ON p.id = o.\"partnerId\" WHERE c.\"organizationId\" = ? AND c.status = 'pending' ORDER BY"
                + " c.\"createdAt\"").param(org()))) {
            out.add(wire(r));
        }
        return out;
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────────────────

    private void apply(String changeId) {
        String orgId = org();
        Map<String, Object> cr = SqlRows.one(jdbc.sql("SELECT * FROM \"PoChangeRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(changeId, orgId));
        String poId = (String) cr.get("purchaseOrderId");
        Map<String, Object> po = lockOpen(poId);
        Map<String, Map<String, Object>> lines = lines(poId);
        ChangeInput in = parse(cr.get("changes"));
        validateAndTotal(po, lines, in);
        int revision = ((Number) po.get("revision")).intValue();
        if (revision == 0) {
            snapshot(poId, 0, null);
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        Map<String, LineChange> byLine = new HashMap<>();
        if (in.lines() != null) {
            in.lines().forEach(l -> byLine.put(l.lineId(), l));
        }
        for (Map<String, Object> l : lines.values()) {
            LineChange c = byLine.get((String) l.get("id"));
            BigDecimal qty = c != null && c.quantity() != null ? c.quantity() : Money.dec(l.get("quantity"));
            BigDecimal price = c != null && c.unitPrice() != null ? c.unitPrice() : Money.dec(l.get("unitPrice"));
            BigDecimal lineSub = qty.multiply(price);
            subtotal = subtotal.add(lineSub);
            tax = tax.add(Money.div(lineSub.multiply(Money.dec(l.get("taxRate"))), BigDecimal.valueOf(100)));
            if (c == null) {
                continue;
            }
            BigDecimal delta = qty.subtract(Money.dec(l.get("quantity")));
            jdbc.sql("UPDATE \"PurchaseOrderLine\" SET quantity = ?, \"unitPrice\" = ?, subtotal = ?, version = version + 1"
                    + " WHERE id = ? AND \"organizationId\" = ?").params(qty, price, lineSub, l.get("id"), orgId).update();
            if (delta.signum() != 0 && l.get("requestLineId") != null) {
                jdbc.sql("UPDATE \"PurchaseRequestLine\" SET \"orderedQuantity\" = GREATEST(0, \"orderedQuantity\" + ?)"
                        + " WHERE id = ? AND \"organizationId\" = ?").params(delta, l.get("requestLineId"), orgId).update();
                jdbc.sql("UPDATE \"PurchaseRequest\" SET status = 'approved', \"updatedAt\" = " + NOW + " WHERE status ="
                        + " 'converted' AND id = (SELECT \"purchaseRequestId\" FROM \"PurchaseRequestLine\" WHERE id = ?)"
                        + " AND EXISTS (SELECT 1 FROM \"PurchaseRequestLine\" x WHERE x.\"purchaseRequestId\" ="
                        + " \"PurchaseRequest\".id AND x.quantity > x.\"orderedQuantity\")")
                        .param(l.get("requestLineId")).update();
            }
        }
        BigDecimal total = subtotal.add(tax);
        boolean allReceived = jdbc.sql("SELECT count(*) FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ? AND"
                + " \"receivedQuantity\" + \"cancelledQuantity\" < quantity").param(poId).query(Long.class).single() == 0;
        String status = allReceived ? "received" : (String) po.get("status");
        jdbc.sql("UPDATE \"PurchaseOrder\" SET subtotal = ?, \"taxAmount\" = ?, \"totalAmount\" = ?,"
                        + " \"expectedDeliveryDate\" = COALESCE(?, \"expectedDeliveryDate\"), status = ?::\"PurchaseOrderStatus\","
                        + " revision = revision + 1, \"acknowledgedAt\" = NULL, version = version + 1, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(subtotal, tax, total, SqlRows.ts(ProcurementSql.date(in.expectedDeliveryDate())), status, poId,
                        orgId)
                .update();
        snapshot(poId, revision + 1, changeId);
        jdbc.sql("UPDATE \"PoChangeRequest\" SET status = 'applied', \"newTotal\" = ?, \"appliedAt\" = " + NOW
                + ", \"updatedAt\" = " + NOW + " WHERE id = ?").params(total, changeId).update();
        commitments.sync(poId);
        audit.recordInTx(AuditInput.of("PurchaseOrder", poId, "update", Obj.of("totalAmount",
                JsNumbers.dec(Money.dec(po.get("totalAmount"))), "revision", revision), Obj.of("totalAmount",
                JsNumbers.dec(total), "revision", revision + 1, "changeRequestId", changeId)));
        events.publish("purchase_order.changed", Obj.of("organizationId", orgId, "orderId", poId, "changeRequestId",
                changeId, "revision", revision + 1));
    }

    private BigDecimal validateAndTotal(Map<String, Object> po, Map<String, Map<String, Object>> lines, ChangeInput in) {
        Map<String, LineChange> byLine = new HashMap<>();
        if (in.lines() != null) {
            for (LineChange c : in.lines()) {
                Map<String, Object> l = lines.get(c.lineId());
                if (l == null) {
                    throw ApiException.badRequest("Line " + c.lineId() + " is not on this order");
                }
                if (byLine.put(c.lineId(), c) != null) {
                    throw ApiException.badRequest("Line " + l.get("description") + " is listed twice");
                }
                if (l.get("agreementLineId") != null) {
                    throw ApiException.badRequest("Line \"" + l.get("description") + "\" was released from a purchase"
                            + " agreement — change the release on the agreement instead");
                }
                BigDecimal floor = Money.dec(l.get("receivedQuantity")).add(Money.dec(l.get("cancelledQuantity")));
                if (c.quantity() != null && (c.quantity().signum() <= 0 || c.quantity().compareTo(floor) < 0)) {
                    throw ApiException.badRequest("Quantity of \"" + l.get("description") + "\" cannot be below "
                            + JsNumbers.dec(floor.max(BigDecimal.ZERO)) + " (already received)"
                            + (floor.signum() == 0 ? " and must be more than 0" : ""));
                }
                if (c.unitPrice() != null) {
                    if (c.unitPrice().signum() < 0) {
                        throw ApiException.badRequest("Unit price must not be negative");
                    }
                    if (Money.dec(l.get("receivedQuantity")).signum() > 0
                            && c.unitPrice().compareTo(Money.dec(l.get("unitPrice"))) != 0) {
                        throw ApiException.badRequest("The price of \"" + l.get("description") + "\" cannot change after"
                                + " goods were received — raise a supplier claim or debit note instead");
                    }
                }
            }
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (Map<String, Object> l : lines.values()) {
            LineChange c = byLine.get((String) l.get("id"));
            BigDecimal qty = c != null && c.quantity() != null ? c.quantity() : Money.dec(l.get("quantity"));
            BigDecimal price = c != null && c.unitPrice() != null ? c.unitPrice() : Money.dec(l.get("unitPrice"));
            BigDecimal lineSub = qty.multiply(price);
            subtotal = subtotal.add(lineSub);
            tax = tax.add(Money.div(lineSub.multiply(Money.dec(l.get("taxRate"))), BigDecimal.valueOf(100)));
        }
        return subtotal.add(tax);
    }

    private void snapshot(String poId, int revision, String changeId) {
        Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT \"orderNumber\", \"expectedDeliveryDate\", subtotal,"
                + " \"taxAmount\", \"totalAmount\", \"currencyCode\" FROM \"PurchaseOrder\" WHERE id = ?").param(poId));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"lineNumber\", \"productId\", description,"
                + " quantity, \"receivedQuantity\", \"unitPrice\", \"taxRate\", subtotal FROM \"PurchaseOrderLine\" WHERE"
                + " \"purchaseOrderId\" = ? ORDER BY \"lineNumber\", id").param(poId))) {
            lines.add(Obj.of("id", l.get("id"), "lineNumber", l.get("lineNumber"), "productId", l.get("productId"),
                    "description", l.get("description"), "quantity", JsNumbers.dec(Money.dec(l.get("quantity"))),
                    "receivedQuantity", JsNumbers.dec(Money.dec(l.get("receivedQuantity"))), "unitPrice",
                    JsNumbers.dec(Money.dec(l.get("unitPrice"))), "taxRate", JsNumbers.dec(Money.dec(l.get("taxRate"))),
                    "subtotal", JsNumbers.dec(Money.dec(l.get("subtotal")))));
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("orderNumber", po.get("orderNumber"));
        snap.put("expectedDeliveryDate", po.get("expectedDeliveryDate") == null ? null
                : String.valueOf(po.get("expectedDeliveryDate")));
        snap.put("currencyCode", po.get("currencyCode"));
        snap.put("subtotal", JsNumbers.dec(Money.dec(po.get("subtotal"))));
        snap.put("taxAmount", JsNumbers.dec(Money.dec(po.get("taxAmount"))));
        snap.put("totalAmount", JsNumbers.dec(Money.dec(po.get("totalAmount"))));
        snap.put("lines", lines);
        jdbc.sql("INSERT INTO \"PoRevision\" (id, \"organizationId\", \"purchaseOrderId\", revision, \"changeRequestId\","
                        + " snapshot, \"createdBy\", \"createdAt\") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, " + NOW + ")"
                        + " ON CONFLICT (\"purchaseOrderId\", revision) DO NOTHING")
                .params(UUID.randomUUID().toString(), org(), poId, revision, changeId, EntityJson.toJson(snap), user())
                .update();
    }

    private Map<String, Object> lockOpen(String poId) {
        Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT id, status::text AS status, \"branchId\", \"totalAmount\","
                + " revision FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " FOR UPDATE").params(poId, org()));
        if (po == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        if (!OPEN.contains(po.get("status"))) {
            throw ApiException.badRequest("Only an open order can be changed (status: " + po.get("status") + ")");
        }
        return po;
    }

    private Map<String, Object> lockPending(String changeId) {
        Map<String, Object> cr = SqlRows.one(jdbc.sql("SELECT * FROM \"PoChangeRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(changeId, org()));
        if (cr == null) {
            throw ApiException.notFound("Change request not found");
        }
        if (!"pending".equals(cr.get("status"))) {
            throw ApiException.badRequest("This change was already decided (status: " + cr.get("status") + ")");
        }
        return cr;
    }

    private void requireOrder(String poId) {
        if (jdbc.sql("SELECT count(*) FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\""
                + " IS NULL").params(poId, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Purchase order not found");
        }
    }

    private Map<String, Map<String, Object>> lines(String poId) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\""
                + " = ? AND \"organizationId\" = ? ORDER BY \"lineNumber\", id FOR UPDATE").params(poId, org()))) {
            out.put((String) l.get("id"), l);
        }
        return out;
    }

    private static Map<String, Object> changesJson(ChangeInput in) {
        List<Map<String, Object>> lines = new ArrayList<>();
        if (in.lines() != null) {
            for (LineChange c : in.lines()) {
                lines.add(Obj.of("lineId", c.lineId(), "quantity", c.quantity() == null ? null : JsNumbers.dec(c.quantity()),
                        "unitPrice", c.unitPrice() == null ? null : JsNumbers.dec(c.unitPrice())));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expectedDeliveryDate", in.expectedDeliveryDate() == null || in.expectedDeliveryDate().isBlank() ? null
                : in.expectedDeliveryDate());
        m.put("lines", lines);
        return m;
    }

    private static ChangeInput parse(Object json) {
        tools.jackson.databind.JsonNode n = json instanceof tools.jackson.databind.JsonNode j ? j
                : EntityJson.tree(String.valueOf(json));
        List<LineChange> lines = new ArrayList<>();
        for (tools.jackson.databind.JsonNode l : n.path("lines")) {
            lines.add(new LineChange(l.path("lineId").asString(), dec(l.get("quantity")), dec(l.get("unitPrice"))));
        }
        tools.jackson.databind.JsonNode d = n.get("expectedDeliveryDate");
        return new ChangeInput("", d == null || d.isNull() ? null : d.asString(), lines);
    }

    private static BigDecimal dec(tools.jackson.databind.JsonNode n) {
        return n == null || n.isNull() ? null : new BigDecimal(n.asString());
    }

    private static Map<String, Object> wire(Map<String, Object> r) {
        r.put("oldTotal", JsNumbers.dec(Money.dec(r.get("oldTotal"))));
        r.put("newTotal", JsNumbers.dec(Money.dec(r.get("newTotal"))));
        return r;
    }
}
