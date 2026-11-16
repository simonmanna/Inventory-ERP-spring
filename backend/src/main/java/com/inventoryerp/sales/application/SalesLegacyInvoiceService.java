package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.fleet.application.DeliveryInvoicing;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase A A4 (plan §6): historical Fleet goods invoices on sales orders. Before A4 a delivery order of a sales order
 * could invoice the order's goods itself; such an order is blocked from Sales goods invoicing until a reviewer matches
 * the Fleet invoice lines to the order lines and a <em>second</em> user approves. Approval writes audited
 * {@code SalesInvoiceLink} rows with {@code origin = legacy_fleet} in one transaction and the order unblocks. An
 * exception keeps it blocked until a credit note or another decision allows a reconciliation. Nothing is moved without
 * an approved review.
 */
@Service
public class SalesLegacyInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(SalesLegacyInvoiceService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SalesDb db;
    private final SalesOrderService orders;
    private final SalesBillingService billing;
    private final DeliveryInvoicing deliveryInvoicing;
    private final AuditService audit;

    public SalesLegacyInvoiceService(SalesDb db, SalesOrderService orders, SalesBillingService billing,
            DeliveryInvoicing deliveryInvoicing, AuditService audit) {
        this.db = db;
        this.orders = orders;
        this.billing = billing;
        this.deliveryInvoicing = deliveryInvoicing;
        this.audit = audit;
    }

    /**
     * The exception queue (and the pre-flight report): every sales order with a live Fleet goods invoice that is not
     * Sales' own and has no reconciled review, with its review if one exists.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.rows("""
                SELECT o.id AS "orderId", o.number AS "orderNumber", p.name AS "partnerName", f.id AS "deliveryOrderId",
                       f.code AS "deliveryOrderCode", d.id AS "documentId", d."documentNumber", d.status::text AS "documentStatus",
                       d."totalAmount", r.id AS "reviewId", r.status AS "reviewStatus"
                  FROM "FleetDeliveryOrder" f
                  JOIN "SalesOrder" o ON o.id = f."sourceId"
                  JOIN "Document" d ON d.id = f."goodsInvoiceId"
                  LEFT JOIN "Partner" p ON p.id = o."partnerId"
                  LEFT JOIN "SalesLegacyInvoiceReview" r ON r."orderId" = o.id AND r."documentId" = d.id
                 WHERE f."organizationId" = ? AND f."sourceType" = 'sales_order'
                   AND d.status::text NOT IN ('cancelled', 'rejected')
                   AND NOT EXISTS (SELECT 1 FROM "SalesInvoiceLink" k WHERE k."orderId" = o.id AND k."documentId" = d.id
                                     AND k.origin = 'sales')
                   AND COALESCE(r.status, 'open') <> 'reconciled'
                 ORDER BY o.number, f.code""", org())) {
            out.add(r);
        }
        return out;
    }

    /** One order's historical Fleet invoices with the proposed line matches and their reviews. */
    @Transactional(readOnly = true)
    public Map<String, Object> forOrder(String orderId) {
        Map<String, Object> o = orders.require(orderId);
        List<Map<String, Object>> invoices = new ArrayList<>();
        for (Map<String, Object> g : billing.legacyInvoices(orderId, deliveryInvoicing.goodsInvoices(orderId))) {
            Map<String, Object> m = new LinkedHashMap<>(g);
            m.put("proposedMatches", deliveryInvoicing.legacyInvoiceLines(str(g.get("deliveryOrderId")),
                    str(g.get("documentId"))));
            m.put("review", review(orderId, str(g.get("documentId"))));
            invoices.add(m);
        }
        return Obj.of("orderId", orderId, "orderNumber", o.get("number"), "blocked", !invoices.isEmpty(), "invoices",
                invoices);
    }

    private Map<String, Object> review(String orderId, String documentId) {
        Map<String, Object> r = db.row("SELECT * FROM \"SalesLegacyInvoiceReview\" WHERE \"orderId\" = ? AND"
                + " \"documentId\" = ? AND \"organizationId\" = ?", orderId, documentId, org());
        if (r != null && r.get("matches") != null) {
            r.put("matches", JSON.readValue(r.get("matches").toString(), List.class));
        }
        return r;
    }

    /**
     * Record (or replace, while open) the reviewer's matches of a Fleet invoice: {@code {matches: [{documentLineId,
     * orderLineId, quantity}], notes}}. Each Fleet invoice line is matched at most once; the order lines must belong to
     * the order; a match beyond what the order line can carry is refused (use {@link #exception} instead).
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> submit(String orderId, String documentId, Map<String, Object> in) {
        Map<String, Object> o = orders.lock(orderId);
        Map<String, Object> g = billing.legacyInvoices(orderId, deliveryInvoicing.goodsInvoices(orderId)).stream()
                .filter(x -> documentId.equals(x.get("documentId"))).findFirst()
                .orElseThrow(() -> ApiException.notFound("No historical Fleet invoice " + documentId + " on this order"));
        Map<String, Object> existing = review(orderId, documentId);
        if (existing != null && !"open".equals(existing.get("status"))) {
            throw ApiException.conflict("This review is " + existing.get("status"));
        }
        List<Map<String, Object>> raw = in.get("matches") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (raw.isEmpty()) {
            throw ApiException.badRequest("matches should not be empty");
        }
        Map<String, BigDecimal> invoiceLines = new LinkedHashMap<>();
        for (Map<String, Object> dl : deliveryInvoicing.legacyInvoiceLines(str(g.get("deliveryOrderId")), documentId)) {
            invoiceLines.put(str(dl.get("documentLineId")), dec(dl.get("quantity")));
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        Map<String, BigDecimal> perOrderLine = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> m : raw) {
            String docLine = str(m.get("documentLineId"));
            String orderLine = str(m.get("orderLineId"));
            BigDecimal qty = dec(m.get("quantity"));
            if (!invoiceLines.containsKey(docLine) || !seen.add(docLine)) {
                throw ApiException.badRequest("Each Fleet invoice line can be matched once");
            }
            if (qty.signum() <= 0 || qty.compareTo(invoiceLines.get(docLine)) > 0) {
                throw ApiException.badRequest("A matched quantity must be above 0 and at most the invoice line's quantity");
            }
            if (db.count("SELECT count(*) FROM \"SalesOrderLine\" WHERE id = ? AND \"orderId\" = ?",
                    List.of(orderLine, orderId)) == 0) {
                throw ApiException.badRequest("Order line not found");
            }
            perOrderLine.merge(orderLine, qty, BigDecimal::add);
            matches.add(Obj.of("documentLineId", docLine, "orderLineId", orderLine, "quantity", JsNumbers.js(qty)));
        }
        for (Map.Entry<String, BigDecimal> e : perOrderLine.entrySet()) {
            Map<String, Object> l = db.row("SELECT description, quantity - \"qtyCancelled\" AS ordered,"
                    + " COALESCE((SELECT sum(k.quantity) FROM \"SalesInvoiceLink\" k JOIN \"Document\" d ON d.id ="
                    + " k.\"documentId\" WHERE k.\"orderLineId\" = l.id AND k.kind = 'regular' AND d.status::text NOT IN"
                    + " ('cancelled', 'rejected')), 0) AS linked FROM \"SalesOrderLine\" l WHERE l.id = ?", e.getKey());
            if (dec(l.get("linked")).add(e.getValue()).compareTo(dec(l.get("ordered"))) > 0) {
                throw ApiException.badRequest("The match puts order line \"" + l.get("description") + "\" above its"
                        + " ordered quantity; record an exception instead");
            }
        }
        String json = JSON.writeValueAsString(matches);
        String notes = blank(str(in.get("notes"))) ? null : str(in.get("notes")).trim();
        String id;
        if (existing == null) {
            id = SalesDb.newId();
            db.update("INSERT INTO \"SalesLegacyInvoiceReview\" (id, \"organizationId\", \"orderId\", \"deliveryOrderId\","
                    + " \"documentId\", matches, notes, \"reviewedById\", \"reviewedAt\") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?,"
                    + " now())", id, org(), orderId, g.get("deliveryOrderId"), documentId, json, notes, user());
        } else {
            id = str(existing.get("id"));
            db.update("UPDATE \"SalesLegacyInvoiceReview\" SET matches = ?::jsonb, notes = ?, \"reviewedById\" = ?,"
                    + " \"reviewedAt\" = now(), \"updatedAt\" = now() WHERE id = ?", json, notes, user(), id);
        }
        audit.record(AuditInput.of("SalesLegacyInvoiceReview", id, existing == null ? "create" : "update",
                existing, Obj.of("order", o.get("number"), "document", g.get("documentNumber"), "matches", matches)));
        return review(orderId, documentId);
    }

    /**
     * Second-person approval: writes one {@code legacy_fleet} regular link per matched order line (the review id
     * recorded on each), closes the review as reconciled and recomputes the order's invoiced quantities.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> approve(String reviewId, Map<String, Object> in) {
        Map<String, Object> peek = db.row("SELECT \"orderId\" FROM \"SalesLegacyInvoiceReview\" WHERE id = ? AND"
                + " \"organizationId\" = ?", reviewId, org());
        if (peek == null) {
            throw ApiException.notFound("Legacy invoice review not found");
        }
        String orderId = str(peek.get("orderId"));
        orders.lock(orderId); // lock order: sales order first, then the review
        Map<String, Object> r = lockReview(reviewId);
        if (!"open".equals(r.get("status"))) {
            throw ApiException.conflict("This review is " + r.get("status"));
        }
        if (user() != null && user().equals(r.get("reviewedById"))) {
            throw ApiException.forbidden("The reviewer cannot approve their own legacy invoice review");
        }
        List<Map<String, Object>> matches = JSON.readValue(r.get("matches").toString(), List.class);
        Map<String, BigDecimal> perLine = new LinkedHashMap<>();
        Map<String, String> docLine = new LinkedHashMap<>();
        for (Map<String, Object> m : matches) {
            String line = str(m.get("orderLineId"));
            perLine.merge(line, dec(m.get("quantity")), BigDecimal::add);
            docLine.merge(line, str(m.get("documentLineId")), (a, b) -> null);
        }
        for (Map.Entry<String, BigDecimal> e : perLine.entrySet()) {
            db.update("INSERT INTO \"SalesInvoiceLink\" (id, \"organizationId\", \"orderId\", \"orderLineId\", \"documentId\","
                    + " kind, quantity, amount, \"documentLineId\", origin, \"legacyReviewId\") VALUES (?, ?, ?, ?, ?,"
                    + " 'regular', ?, 0, ?, 'legacy_fleet', ?)", SalesDb.newId(), org(), orderId, e.getKey(),
                    r.get("documentId"), e.getValue(), docLine.get(e.getKey()), reviewId);
        }
        String notes = in == null || blank(str(in.get("notes"))) ? null : str(in.get("notes")).trim();
        db.update("UPDATE \"SalesLegacyInvoiceReview\" SET status = 'reconciled', \"approvedById\" = ?, \"approvedAt\" ="
                + " now(), notes = COALESCE(?, notes), \"updatedAt\" = now() WHERE id = ?", user(), notes, reviewId);
        billing.recomputeInvoiced(orderId);
        audit.record(AuditInput.of("SalesLegacyInvoiceReview", reviewId, "approve", Obj.of("status", "open"),
                Obj.of("status", "reconciled", "links", perLine.size())));
        return review(orderId, str(r.get("documentId")));
    }

    /** The Fleet invoice cannot be matched (e.g. it billed more than the order line): the order stays blocked. */
    @Transactional
    public Map<String, Object> exception(String reviewId, Map<String, Object> in) {
        Map<String, Object> r = lockReview(reviewId);
        if ("reconciled".equals(r.get("status"))) {
            throw ApiException.conflict("This review is reconciled");
        }
        String notes = in == null ? null : str(in.get("notes"));
        if (blank(notes)) {
            throw ApiException.badRequest("notes should not be empty");
        }
        db.update("UPDATE \"SalesLegacyInvoiceReview\" SET status = 'exception', notes = ?, \"updatedAt\" = now() WHERE"
                + " id = ?", notes.trim(), reviewId);
        audit.record(AuditInput.of("SalesLegacyInvoiceReview", reviewId, "update", Obj.of("status", r.get("status")),
                Obj.of("status", "exception", "notes", notes.trim())));
        log.warn("Legacy Fleet invoice review {} of order {} is an exception; the order stays blocked", reviewId,
                r.get("orderId"));
        return review(str(r.get("orderId")), str(r.get("documentId")));
    }

    /** Reopen an exception once the cause is resolved (credit note, decision), so it can be reviewed again. */
    @Transactional
    public Map<String, Object> reopen(String reviewId) {
        Map<String, Object> r = lockReview(reviewId);
        if (!"exception".equals(r.get("status"))) {
            throw ApiException.conflict("Only an exception can be reopened");
        }
        db.update("UPDATE \"SalesLegacyInvoiceReview\" SET status = 'open', \"updatedAt\" = now() WHERE id = ?", reviewId);
        audit.record(AuditInput.of("SalesLegacyInvoiceReview", reviewId, "update", Obj.of("status", "exception"),
                Obj.of("status", "open")));
        return review(str(r.get("orderId")), str(r.get("documentId")));
    }

    private Map<String, Object> lockReview(String id) {
        Map<String, Object> r = db.row("SELECT * FROM \"SalesLegacyInvoiceReview\" WHERE id = ? AND \"organizationId\" = ?"
                + " FOR UPDATE", id, org());
        if (r == null) {
            throw ApiException.notFound("Legacy invoice review not found");
        }
        return r;
    }
}
