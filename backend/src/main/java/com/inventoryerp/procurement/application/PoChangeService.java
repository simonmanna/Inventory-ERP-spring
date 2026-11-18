package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.approvals.ApprovalsService;
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
import java.math.RoundingMode;
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
    private final ApprovalsService approvals;
    private final ProcurementBudgetService budgets;
    private final ProcurementDelegationService delegations;
    private final TransactionTemplate tx;

    public PoChangeService(JdbcClient jdbc, AuditService audit, EventBus events, PurchaseCommitments commitments,
            ProcurementPolicyService policies, PlatformTransactionManager txm, ApprovalsService approvals,
            ProcurementBudgetService budgets, ProcurementDelegationService delegations) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.commitments = commitments;
        this.policies = policies;
        this.approvals = approvals;
        this.budgets = budgets;
        this.delegations = delegations;
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
        List<String> warnings = new ArrayList<>();
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
            // h03-F6 / h04-F2: a change that raises the order meets the limits of a new order of its new value: the
            // requester's delegated purchasing limit now, the approval workflow's amount bands before it applies.
            String workflowRequest = null;
            if (newTotal.compareTo(oldTotal) > 0) {
                if (jdbc.sql("SELECT count(*) FROM \"PoChangeRequest\" WHERE \"purchaseOrderId\" = ? AND status ="
                        + " 'pending'").param(poId).query(Long.class).single() > 0) {
                    throw ApiException.conflict("This order already has an open change request — decide it first");
                }
                BigDecimal rate = rate(po);
                delegations.enforce((String) po.get("branchId"), baseValue(lines, in, rate));
                workflowRequest = workflowGate(po, newTotal.multiply(rate), oldTotal.multiply(rate), id, number);
                needsApproval = needsApproval || workflowRequest != null;
            }
            try {
                jdbc.sql("INSERT INTO \"PoChangeRequest\" (id, \"organizationId\", \"purchaseOrderId\", \"changeNumber\","
                                + " status, reason, changes, \"oldTotal\", \"newTotal\", \"needsApproval\", \"requestedBy\","
                                + " \"approvalRequestId\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, 'pending', ?,"
                                + " ?::jsonb, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")")
                        .params(id, orgId, poId, number, in.reason().trim(), EntityJson.toJson(changesJson(in)), oldTotal,
                                newTotal, needsApproval, user(), workflowRequest)
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw ApiException.conflict("This order already has an open change request — decide it first");
            }
            audit.recordInTx(AuditInput.of("PoChangeRequest", id, "create", null, Obj.of("purchaseOrderId", poId,
                    "oldTotal", JsNumbers.dec(oldTotal), "newTotal", JsNumbers.dec(newTotal), "needsApproval",
                    needsApproval, "approvalRequestId", workflowRequest)));
            if (!needsApproval) {
                warnings.addAll(apply(id));
            }
        });
        return withWarnings(get(id), warnings);
    }

    /**
     * Approve and apply a pending change (not by the person who asked). A change that raised the order past its
     * approved amount applies only once the order's approval workflow approved the new value (h03-F6).
     */
    public Map<String, Object> approve(String changeId, String note) {
        List<String> warnings = new ArrayList<>();
        tx.executeWithoutResult(st -> {
            Map<String, Object> cr = lockPending(changeId);
            if (user() == null) {
                // h04-F11: a token without a user cannot prove it is not the requester.
                throw ApiException.forbidden("A change cannot be approved without a signed-in user; approve it from a"
                        + " user account");
            }
            if (user().equals(cr.get("requestedBy"))) {
                throw ApiException.forbidden("You cannot approve a change you requested — another approver must decide");
            }
            jdbc.sql("UPDATE \"PoChangeRequest\" SET status = 'approved', \"decidedBy\" = ?, \"decidedAt\" = " + NOW
                    + ", \"decisionNote\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?").params(user(), note, changeId)
                    .update();
            warnings.addAll(apply(changeId));
        });
        return withWarnings(get(changeId), warnings);
    }

    /** Reject a pending change. */
    public Map<String, Object> reject(String changeId, String note) {
        if (note == null || note.isBlank()) {
            throw ApiException.badRequest("A reason is required to reject a change");
        }
        tx.executeWithoutResult(st -> {
            Map<String, Object> cr = lockPending(changeId);
            withdrawWorkflow(cr, "The order change was rejected");
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
            withdrawWorkflow(cr, "The order change was withdrawn");
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

    /** Apply a change; returns the budget warnings to show (warn-mode budgets the raised order now exceeds). */
    private List<String> apply(String changeId) {
        String orgId = org();
        Map<String, Object> cr = SqlRows.one(jdbc.sql("SELECT * FROM \"PoChangeRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(changeId, orgId));
        requireWorkflowApproved(cr);
        String poId = (String) cr.get("purchaseOrderId");
        Map<String, Object> po = lockOpen(poId);
        Map<String, Map<String, Object>> lines = lines(poId);
        ChangeInput in = parse(cr.get("changes"));
        BigDecimal newTotal = validateAndTotal(po, lines, in);
        List<String> warnings = List.of();
        if (newTotal.compareTo(Money.dec(po.get("totalAmount"))) > 0) {
            warnings = checkBudgets(po, lines, in);
        }
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
        return warnings;
    }

    // ── limits for a raised order (h03-F6 / h04-F2) ─────────────────────────────────────────────────────────────

    /** The order's rate to the functional currency. */
    private static BigDecimal rate(Map<String, Object> po) {
        BigDecimal rate = Money.dec(po.get("exchangeRate"));
        return rate.signum() > 0 ? rate : BigDecimal.ONE;
    }

    /** The changed line's quantity and price, else the line's own. */
    private static BigDecimal[] qtyPrice(Map<String, Object> l, Map<String, LineChange> byLine) {
        LineChange c = byLine.get((String) l.get("id"));
        return new BigDecimal[] {c != null && c.quantity() != null ? c.quantity() : Money.dec(l.get("quantity")),
            c != null && c.unitPrice() != null ? c.unitPrice() : Money.dec(l.get("unitPrice"))};
    }

    private static Map<String, LineChange> byLine(ChangeInput in) {
        Map<String, LineChange> out = new HashMap<>();
        if (in.lines() != null) {
            in.lines().forEach(l -> out.put(l.lineId(), l));
        }
        return out;
    }

    /** The changed order's value in base currency, excluding tax: what a delegated purchasing limit is measured on. */
    private static BigDecimal baseValue(Map<String, Map<String, Object>> lines, ChangeInput in, BigDecimal rate) {
        Map<String, LineChange> byLine = byLine(in);
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> l : lines.values()) {
            BigDecimal[] qp = qtyPrice(l, byLine);
            sum = sum.add(qp[0].multiply(qp[1]).multiply(rate));
        }
        return sum.setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * The approval workflow sees a raised order as a new order of its new value (functional currency). When that is
     * above every amount the order was approved for, a {@code purchase_order} request is raised; {@code null} when the
     * approved amount covers it or no workflow step applies to the new value.
     */
    private String workflowGate(Map<String, Object> po, BigDecimal newAmount, BigDecimal oldAmount, String changeId,
            int changeNumber) {
        String poId = (String) po.get("id");
        BigDecimal approved = null;
        for (String raw : jdbc.sql("SELECT snapshot->>'amount' FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = 'purchase_order' AND \"entityId\" = ? AND status::text = 'approved'")
                .params(org(), poId).query(String.class).list()) {
            try {
                BigDecimal a = raw == null ? null : new BigDecimal(raw.trim());
                approved = a == null ? approved : approved == null ? a : approved.max(a);
            } catch (NumberFormatException e) {
                // a snapshot without a readable amount approves nothing
            }
        }
        if (approved != null && newAmount.compareTo(approved.add(new BigDecimal("0.005"))) <= 0) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("amount", JsNumbers.js(newAmount.setScale(6, RoundingMode.HALF_UP)));
        snapshot.put("previousAmount", JsNumbers.js(oldAmount.setScale(6, RoundingMode.HALF_UP)));
        snapshot.put("partnerId", po.get("partnerId"));
        snapshot.put("orderNumber", po.get("orderNumber"));
        snapshot.put("createdBy", user());
        if (po.get("branchId") != null) {
            snapshot.put("branchId", po.get("branchId"));
        }
        if (po.get("departmentId") != null) {
            snapshot.put("departmentId", po.get("departmentId"));
        }
        snapshot.put("changeRequestId", changeId);
        snapshot.put("changeNumber", changeNumber);
        Map<String, Object> req = approvals.requestApproval("purchase_order", poId, snapshot, null);
        return req == null ? null : (String) req.get("id");
    }

    /** A change raised to the approval workflow applies only after that request was approved. */
    private void requireWorkflowApproved(Map<String, Object> cr) {
        Object requestId = cr.get("approvalRequestId");
        if (requestId == null) {
            return;
        }
        String status = jdbc.sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ? AND \"organizationId\" = ?")
                .params(requestId, org()).query(String.class).optional().orElse("missing");
        if ("pending".equals(status)) {
            throw ApiException.badRequest("This change cannot be applied while the order's approval workflow is"
                    + " deciding the new total (request " + requestId + "); approve that request first");
        }
        if (!"approved".equals(status)) {
            throw ApiException.badRequest("This change cannot be applied: the order's approval workflow did not approve"
                    + " the new total (" + status + "); reject or withdraw the change");
        }
    }

    /** Withdraw the change's still-pending workflow request (a no-op when there is none or it was decided). */
    private void withdrawWorkflow(Map<String, Object> cr, String reason) {
        if (cr.get("approvalRequestId") != null) {
            approvals.withdraw((String) cr.get("approvalRequestId"), reason);
        }
    }

    /**
     * Budget control on the raised order, as on a new order: the procurement budgets of its branch and department
     * (blocking ones refuse with 409 under the budget row lock) and the {@code budget.control} policy on the accounting
     * budgets, with each line's request cost centre. The order's own earlier value is left out of both.
     */
    private List<String> checkBudgets(Map<String, Object> po, Map<String, Map<String, Object>> lines, ChangeInput in) {
        Map<String, LineChange> byLine = byLine(in);
        BigDecimal rate = rate(po);
        List<ProcurementBudgetService.DocLine> docLines = new ArrayList<>();
        List<PurchaseCommitments.Draft> drafts = new ArrayList<>();
        for (Map<String, Object> l : lines.values()) {
            BigDecimal[] qp = qtyPrice(l, byLine);
            BigDecimal cancelled = Money.dec(l.get("cancelledQuantity"));
            BigDecimal open = qp[0].subtract(Money.dec(l.get("receivedQuantity"))).subtract(cancelled).max(BigDecimal.ZERO);
            docLines.add(new ProcurementBudgetService.DocLine((String) l.get("productId"), qp[0].subtract(cancelled)
                    .multiply(qp[1]).multiply(rate).setScale(6, RoundingMode.HALF_UP)));
            drafts.add(new PurchaseCommitments.Draft((String) l.get("id"), (String) l.get("productId"), open, qp[1],
                    (String) l.get("requestLineId")));
        }
        budgets.enforce(new ProcurementBudgetService.Doc(ProcurementBudgetService.ORDER, (String) po.get("id"),
                (String) po.get("branchId"), (String) po.get("departmentId"), docLines, null));
        return commitments.checkBudget((String) po.get("id"), (String) po.get("branchId"),
                (String) po.get("currencyCode"), rate, drafts);
    }

    private static Map<String, Object> withWarnings(Map<String, Object> out, List<String> warnings) {
        if (!warnings.isEmpty()) {
            out.put("warnings", warnings);
        }
        return out;
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
                + " revision, \"departmentId\", \"exchangeRate\", \"currencyCode\", \"partnerId\", \"orderNumber\" FROM"
                + " \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL FOR UPDATE")
                .params(poId, org()));
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
