package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asking for credit beyond the limit (SG-8). A salesperson requests with a reason; a holder of the credit override permission
 * approves or rejects, and who and why stay on the request and the credit decision. Approving confirms the waiting order or
 * releases the held one, and the exposure is recalculated at that moment.
 */
@Service
public class SalesCreditRequestService {

    private final SalesDb db;
    private final CreditService credit;
    private final ObjectProvider<SalesOrderService> orders;
    private final SalesAlerts alerts;
    private final AuditService audit;

    public SalesCreditRequestService(SalesDb db, CreditService credit, ObjectProvider<SalesOrderService> orders, SalesAlerts alerts,
            AuditService audit) {
        this.db = db;
        this.credit = credit;
        this.orders = orders;
        this.alerts = alerts;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Map<String, String> q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = " WHERE r.\"organizationId\" = ?";
        for (String k : List.of("status", "partnerId", "orderId")) {
            if (!blank(q.get(k))) {
                where += " AND r.\"" + k + "\" = ?";
                params.add(q.get(k));
            }
        }
        return db.rows("SELECT r.*, o.number AS \"orderNumber\", o.status AS \"orderStatus\", p.name AS \"partnerName\","
                + " COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\", u.\"lastName\")), ''), u.email) AS \"requestedByName\","
                + " COALESCE(NULLIF(trim(concat_ws(' ', d.\"firstName\", d.\"lastName\")), ''), d.email) AS \"decidedByName\""
                + " FROM \"SalesCreditRequest\" r JOIN \"SalesOrder\" o ON o.id = r.\"orderId\" LEFT JOIN \"Partner\" p ON p.id = r.\"partnerId\""
                + " LEFT JOIN \"User\" u ON u.id = r.\"requestedById\" LEFT JOIN \"User\" d ON d.id = r.\"decidedById\"" + where
                + " ORDER BY r.\"createdAt\" DESC LIMIT 300", params);
    }

    /** Open a request for this order, or return the one already waiting. */
    @Transactional
    public Map<String, Object> request(String orderId, String reason) {
        Map<String, Object> o = orders.getObject().require(orderId);
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        if (java.util.Set.of("cancelled", "done").contains(o.get("status"))) {
            throw ApiException.badRequest("This order is closed");
        }
        return open(o, reason);
    }

    Map<String, Object> open(Map<String, Object> o, String reason) {
        Map<String, Object> waiting = db.row("SELECT * FROM \"SalesCreditRequest\" WHERE \"orderId\" = ? AND status = 'pending'", o.get("id"));
        if (waiting != null) {
            return waiting;
        }
        String partner = str(o.get("partnerId"));
        Map<String, Object> e = credit.exposure(partner, str(o.get("branchId")), str(o.get("id")));
        Map<String, Object> row = db.insert("SalesCreditRequest", Obj.of("orderId", o.get("id"), "partnerId", partner, "amount", o.get("total"),
                "exposureAtRequest", e.get("exposure"), "limitAtRequest", e.get("effectiveLimit"), "reason", reason, "requestedById", user()));
        audit.record(AuditInput.of("SalesCreditRequest", str(row.get("id")), "create", null, Obj.of("order", o.get("number"), "reason", reason)));
        Map<String, Object> p = db.row("SELECT \"creditControllerId\", name FROM \"Partner\" WHERE id = ?", partner);
        if (p != null) {
            alerts.send(str(p.get("creditControllerId")), "sales_credit_request", "Credit request for " + p.get("name"),
                    "Order " + o.get("number") + ": " + reason, Obj.of("entityType", "sales_order", "entityId", o.get("id")));
        }
        return row;
    }

    private Map<String, Object> pending(String id) {
        Map<String, Object> r = db.row("SELECT * FROM \"SalesCreditRequest\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE", id, org());
        if (r == null) {
            throw ApiException.notFound("Credit request not found");
        }
        if (!"pending".equals(r.get("status"))) {
            throw ApiException.badRequest("This credit request was already decided");
        }
        if (user() != null && user().equals(r.get("requestedById"))) {
            throw ApiException.forbidden("You cannot decide your own credit request");
        }
        return r;
    }

    private void decide(Map<String, Object> r, String status, String comment) {
        Map<String, Object> e = credit.exposure(str(r.get("partnerId")));
        db.update("UPDATE \"SalesCreditRequest\" SET status = ?, \"decidedById\" = ?, \"decidedAt\" = " + NOW + ", \"decisionComment\" = ?,"
                + " \"exposureAtDecision\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?", status, user(), comment, dec(e.get("exposure")), r.get("id"));
        audit.record(AuditInput.of("SalesCreditRequest", str(r.get("id")), "approved".equals(status) ? "approve" : "reject", null, Obj.of("comment",
                comment)));
        alerts.send(str(r.get("requestedById")), "sales_credit_decision", "Credit request " + status, comment == null ? "" : comment,
                Obj.of("entityType", "sales_order", "entityId", r.get("orderId")));
    }

    @Transactional
    public Map<String, Object> approve(String id, String comment) {
        Map<String, Object> r = pending(id);
        if (blank(comment)) {
            throw ApiException.badRequest("comment should not be empty");
        }
        decide(r, "approved", comment);
        String orderId = str(r.get("orderId"));
        Map<String, Object> o = db.row("SELECT number, status, \"onHold\", \"holdKind\" FROM \"SalesOrder\" WHERE id = ?", orderId);
        if (Boolean.TRUE.equals(o.get("onHold")) && !"manual".equals(o.get("holdKind"))) {
            orders.getObject().release(orderId);
        } else if (java.util.Set.of("draft", "pending_approval").contains(o.get("status"))) {
            credit.override(str(r.get("partnerId")), "sales_order", orderId, str(o.get("number")), comment);
            orders.getObject().confirmAfterCredit(orderId);
        }
        // The decision's exposure includes the order it just let through.
        db.update("UPDATE \"SalesCreditRequest\" SET \"exposureAtDecision\" = ? WHERE id = ?", dec(credit.exposure(str(r.get("partnerId")))
                .get("exposure")), id);
        return db.row("SELECT * FROM \"SalesCreditRequest\" WHERE id = ?", id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String comment) {
        Map<String, Object> r = pending(id);
        if (blank(comment)) {
            throw ApiException.badRequest("comment should not be empty");
        }
        decide(r, "rejected", comment);
        return db.row("SELECT * FROM \"SalesCreditRequest\" WHERE id = ?", id);
    }

    /** An approved request still covering this much of the order. */
    boolean approvedFor(String orderId, java.math.BigDecimal total) {
        Map<String, Object> r = db.row("SELECT amount FROM \"SalesCreditRequest\" WHERE \"orderId\" = ? AND status = 'approved' ORDER BY \"decidedAt\""
                + " DESC LIMIT 1", orderId);
        return r != null && dec(r.get("amount")).compareTo(total) >= 0;
    }

    /** Open receivables of a customer per branch. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> byBranch(String partnerId) {
        return db.rows("SELECT d.\"branchId\", b.name AS \"branchName\", COALESCE(sum(d.\"amountResidual\" * d.\"exchangeRate\"), 0) AS outstanding,"
                + " COALESCE(sum(d.\"amountResidual\" * d.\"exchangeRate\") FILTER (WHERE d.\"dueDate\" < now()), 0) AS overdue, (SELECT l.\"limitAmount\" FROM"
                + " \"CreditLimit\" l WHERE l.\"organizationId\" = d.\"organizationId\" AND l.\"partnerId\" = d.\"partnerId\" AND l.\"branchId\" = d.\"branchId\" LIMIT 1)"
                + " AS \"branchLimit\" FROM \"Document\" d LEFT JOIN \"Branch\" b ON b.id = d.\"branchId\" WHERE d.\"organizationId\" = ? AND d.\"partnerId\" = ? AND"
                + " d.\"documentType\"::text = 'sales_invoice' AND d.status::text = 'posted' AND d.\"amountResidual\" > 0 GROUP BY d.\"branchId\", b.name,"
                + " d.\"organizationId\", d.\"partnerId\" ORDER BY outstanding DESC", org(), partnerId);
    }
}
