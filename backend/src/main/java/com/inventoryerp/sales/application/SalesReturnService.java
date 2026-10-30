package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.CreditNoteService;
import com.inventoryerp.invoicing.application.CreditNoteService.CreditNoteInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.PaymentService;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales returns / RMA (ADR-S011 phase S7; sales-postings.md P12, P13):
 * {@code requested → approved → received → inspected → closed}, or {@code rejected} / {@code cancelled}.
 *
 * <ul>
 *   <li>Receive: returned goods enter the receiving location at their cost (Dr inventory / Cr COGS, inventory's
 *       return receipt); the order lines' returned quantity is updated. Claims without goods skip receive / inspect.</li>
 *   <li>Inspect: per line restock (stays usable), quarantine (moved to a quarantine location) or scrap (issued as waste:
 *       Dr waste / Cr inventory).</li>
 *   <li>Close: credit note against the order invoice for the invoiced part (never edits the invoice; gross invoiced is
 *       kept, credited grows), the not-yet-invoiced part lowers the billable quantity by a recorded decision; optional
 *       refund (Dr AR / Cr tender) or a replacement order.</li>
 * </ul>
 */
@Service
public class SalesReturnService {

    static final Set<String> REASONS = Set.of("wrong_grade", "damaged", "short_quantity", "excess_quantity",
            "quality_rejection", "other");

    private final SalesDb db;
    private final SalesOrderService orders;
    private final StockService stock;
    private final CreditNoteService creditNotes;
    private final PaymentService payments;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public SalesReturnService(SalesDb db, SalesOrderService orders, StockService stock, CreditNoteService creditNotes,
            PaymentService payments, SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.orders = orders;
        this.stock = stock;
        this.creditNotes = creditNotes;
        this.payments = payments;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Map<String, String> p) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        for (String k : List.of("orderId", "partnerId", "status")) {
            if (!blank(p.get(k))) {
                where.append(" AND r.\"").append(k).append("\" = ?");
                params.add(p.get(k));
            }
        }
        return db.rows("SELECT r.*, o.number AS \"orderNumber\", pa.name AS \"partnerName\" FROM \"SalesReturn\" r JOIN"
                + " \"SalesOrder\" o ON o.id = r.\"orderId\" LEFT JOIN \"Partner\" pa ON pa.id = r.\"partnerId\"" + where
                + " ORDER BY r.\"createdAt\" DESC LIMIT 500", params);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = db.row("SELECT r.*, o.number AS \"orderNumber\", pa.name AS \"partnerName\" FROM"
                + " \"SalesReturn\" r JOIN \"SalesOrder\" o ON o.id = r.\"orderId\" LEFT JOIN \"Partner\" pa ON pa.id ="
                + " r.\"partnerId\" WHERE r.id = ? AND r.\"organizationId\" = ?", id, org());
        if (r == null) {
            throw ApiException.notFound("Return not found");
        }
        r.put("lines", db.rows("SELECT * FROM \"SalesReturnLine\" WHERE \"returnId\" = ? ORDER BY description", id));
        return r;
    }

    // ------------------------------------------------------------------ lifecycle

    /** New return request against a confirmed order: lines {@code [{orderLineId, quantity}]}. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> in) {
        String orderId = str(in.get("orderId"));
        Map<String, Object> o = orders.lock(orderId);
        if (!Set.of("confirmed", "in_fulfilment", "done").contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders can have returns");
        }
        String reason = str(in.get("reason"));
        if (reason == null || !REASONS.contains(reason)) {
            throw ApiException.badRequest("reason must be one of: " + String.join(", ", REASONS.stream().sorted().toList()));
        }
        boolean physical = !Boolean.FALSE.equals(in.get("physicalReturn"));
        List<Map<String, Object>> lines = in.get("lines") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Add at least one line to return");
        }
        int year = LocalDate.now().getYear();
        String id = SalesDb.newId();
        String number = sequence.next("sales_return:" + year, "RMA-" + year + "-", 5);
        db.update("INSERT INTO \"SalesReturn\" (id, \"organizationId\", number, \"orderId\", \"partnerId\", reason,"
                + " \"physicalReturn\", \"receiveLocationId\", \"complaintId\", \"discrepancyId\", notes, \"requestedById\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, org(), number, orderId, o.get("partnerId"), reason,
                physical, blank(str(in.get("receiveLocationId"))) ? o.get("locationId") : str(in.get("receiveLocationId")),
                str(in.get("complaintId")), str(in.get("discrepancyId")), str(in.get("notes")), user());
        for (Map<String, Object> l : lines) {
            Map<String, Object> ol = db.row("SELECT * FROM \"SalesOrderLine\" WHERE id = ? AND \"orderId\" = ?",
                    str(l.get("orderLineId")), orderId);
            if (ol == null) {
                throw ApiException.notFound("Order line not found");
            }
            BigDecimal qty = dec(l.get("quantity"));
            BigDecimal open = returnable(ol);
            if (qty.signum() <= 0 || qty.compareTo(open) > 0) {
                throw ApiException.badRequest("Quantity of " + ol.get("description") + " must be between 0 and "
                        + open.stripTrailingZeros().toPlainString() + " (delivered and not yet returned)");
            }
            db.update("INSERT INTO \"SalesReturnLine\" (id, \"organizationId\", \"returnId\", \"orderLineId\", \"productId\","
                    + " description, quantity) VALUES (?, ?, ?, ?, ?, ?, ?)", SalesDb.newId(), org(), id, ol.get("id"),
                    ol.get("productId"), ol.get("description"), qty);
        }
        if (!blank(str(in.get("complaintId")))) {
            db.update("UPDATE \"CustomerComplaint\" SET \"returnId\" = ?, status = 'investigating', \"updatedAt\" = now()"
                    + " WHERE id = ? AND \"organizationId\" = ?", id, str(in.get("complaintId")), org());
        }
        audit.record(AuditInput.of("SalesReturn", id, "create", null, Obj.of("number", number, "orderId", orderId)));
        return get(id);
    }

    /** Delivered (dispatched, else billable) minus quantities on other open / done returns. */
    private BigDecimal returnable(Map<String, Object> ol) {
        BigDecimal delivered = dec(ol.get("qtyDispatched")).max(dec(ol.get("qtyBillable")));
        BigDecimal claimed = dec(db.row("SELECT COALESCE(sum(l.quantity), 0) AS q FROM \"SalesReturnLine\" l JOIN"
                + " \"SalesReturn\" r ON r.id = l.\"returnId\" WHERE l.\"orderLineId\" = ? AND r.status NOT IN ('rejected',"
                + " 'cancelled')", ol.get("id")).get("q"));
        return delivered.subtract(claimed).max(BigDecimal.ZERO);
    }

    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> r = lock(id, "requested");
        db.update("UPDATE \"SalesReturn\" SET status = 'approved', \"approvedById\" = ?, \"approvedAt\" = now(),"
                + " \"updatedAt\" = now() WHERE id = ?", user(), id);
        audit.record(AuditInput.of("SalesReturn", id, "approve", null, Obj.of("number", r.get("number"))));
        return get(id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> r = lock(id, null);
        if (!Set.of("requested", "approved").contains(r.get("status"))) {
            throw ApiException.badRequest("Only a return not yet received can be rejected");
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'rejected', \"rejectionReason\" = ?, \"updatedAt\" = now() WHERE"
                + " id = ?", blank(reason) ? "Rejected" : reason, id);
        audit.record(AuditInput.of("SalesReturn", id, "reject", null, Obj.of("reason", reason)));
        return get(id);
    }

    /**
     * Receive the goods ({@code lines [{lineId, receivedQty}]}, default the requested quantity) into the receiving
     * location: Dr inventory / Cr COGS at the current cost.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> receive(String id, Map<String, Object> in) {
        Map<String, Object> r = lock(id, "approved");
        if (!Boolean.TRUE.equals(r.get("physicalReturn"))) {
            throw ApiException.badRequest("This return is a claim without goods; close it directly");
        }
        String location = blank(str(in.get("locationId"))) ? str(r.get("receiveLocationId")) : str(in.get("locationId"));
        if (location == null) {
            throw ApiException.badRequest("Choose the location the goods are received into");
        }
        Map<String, BigDecimal> given = new LinkedHashMap<>();
        if (in.get("lines") instanceof List<?> l) {
            for (Map<String, Object> x : (List<Map<String, Object>>) l) {
                given.put(str(x.get("lineId")), dec(x.get("receivedQty")));
            }
        }
        for (Map<String, Object> l : db.rows("SELECT rl.*, ol.\"uomId\", ol.\"uomFactor\", p.\"trackInventory\" FROM"
                + " \"SalesReturnLine\" rl JOIN \"SalesOrderLine\" ol ON ol.id = rl.\"orderLineId\" LEFT JOIN \"Product\" p ON"
                + " p.id = rl.\"productId\" WHERE rl.\"returnId\" = ?", id)) {
            BigDecimal qty = given.getOrDefault(str(l.get("id")), dec(l.get("quantity")));
            if (qty.signum() < 0 || qty.compareTo(dec(l.get("quantity"))) > 0) {
                throw ApiException.badRequest("Received quantity of " + l.get("description") + " is out of range");
            }
            if (qty.signum() > 0 && Boolean.TRUE.equals(l.get("trackInventory"))) {
                stock.receiveReturn(StockMove.of(str(l.get("productId")), location, qty).uom(str(l.get("uomId")),
                        dec(l.get("uomFactor"))).reference(str(r.get("number"))).source("sales_return", id));
            }
            db.update("UPDATE \"SalesReturnLine\" SET \"receivedQty\" = ? WHERE id = ?", qty, l.get("id"));
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'received', \"receiveLocationId\" = ?, \"receivedAt\" = now(),"
                + " \"updatedAt\" = now() WHERE id = ?", location, id);
        returnedProjection(str(r.get("orderId")));
        events.publish(DomainEvents.SALES_RETURN_RECEIVED, Obj.of("v", 1, "organizationId", org(), "returnId", id,
                "number", r.get("number"), "orderId", r.get("orderId")));
        audit.record(AuditInput.of("SalesReturn", id, "update", null, Obj.of("status", "received", "location", location)));
        return get(id);
    }

    /** Inspect: {@code lines [{lineId, disposition: restock|quarantine|scrap, locationId?, notes?}]}. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspect(String id, Map<String, Object> in) {
        Map<String, Object> r = lock(id, "received");
        Map<String, Map<String, Object>> given = new LinkedHashMap<>();
        if (in.get("lines") instanceof List<?> l) {
            for (Map<String, Object> x : (List<Map<String, Object>>) l) {
                given.put(str(x.get("lineId")), x);
            }
        }
        String from = str(r.get("receiveLocationId"));
        for (Map<String, Object> l : db.rows("SELECT rl.*, ol.\"uomId\", ol.\"uomFactor\", p.\"trackInventory\" FROM"
                + " \"SalesReturnLine\" rl JOIN \"SalesOrderLine\" ol ON ol.id = rl.\"orderLineId\" LEFT JOIN \"Product\" p ON"
                + " p.id = rl.\"productId\" WHERE rl.\"returnId\" = ?", id)) {
            Map<String, Object> g = given.getOrDefault(str(l.get("id")), Map.of());
            String disposition = g.get("disposition") == null ? "restock" : str(g.get("disposition"));
            if (!Set.of("restock", "quarantine", "scrap").contains(disposition)) {
                throw ApiException.badRequest("disposition must be one of: quarantine, restock, scrap");
            }
            BigDecimal qty = dec(l.get("receivedQty"));
            String target = str(g.get("locationId"));
            boolean tracked = Boolean.TRUE.equals(l.get("trackInventory")) && qty.signum() > 0;
            if (tracked && "quarantine".equals(disposition)) {
                if (target == null) {
                    throw ApiException.badRequest("Choose the quarantine location for " + l.get("description"));
                }
                if (!target.equals(from)) {
                    StockMove m = StockMove.transfer(str(l.get("productId")), from, target, qty).uom(str(l.get("uomId")),
                            dec(l.get("uomFactor"))).reference(str(r.get("number")));
                    stock.transfer(m);
                }
            } else if (tracked && "scrap".equals(disposition)) {
                stock.issue(StockMove.of(str(l.get("productId")), from, qty).uom(str(l.get("uomId")), dec(l.get(
                        "uomFactor"))).moveType("waste").reference(str(r.get("number"))).source("sales_return", id)
                        .notes("Scrapped return " + r.get("number")));
            }
            db.update("UPDATE \"SalesReturnLine\" SET disposition = ?, \"dispositionLocationId\" = ?, \"inspectionNotes\" = ?"
                    + " WHERE id = ?", disposition, "quarantine".equals(disposition) ? target : from, str(g.get("notes")),
                    l.get("id"));
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'inspected', \"inspectedAt\" = now(), \"updatedAt\" = now() WHERE"
                + " id = ?", id);
        audit.record(AuditInput.of("SalesReturn", id, "update", null, Obj.of("status", "inspected")));
        return get(id);
    }

    /**
     * Close with an outcome: {@code credit_note} (default), {@code refund} (credit note + refund payment:
     * {@code paymentMethod}, {@code accountId}), {@code replacement} (new draft order for the returned quantity at no
     * charge) or {@code none}.
     */
    @Transactional
    public Map<String, Object> close(String id, Map<String, Object> in) {
        Map<String, Object> r = lock(id, null);
        boolean physical = Boolean.TRUE.equals(r.get("physicalReturn"));
        if (physical ? !"inspected".equals(r.get("status")) : !"approved".equals(r.get("status"))) {
            throw ApiException.badRequest(physical ? "Inspect the returned goods before closing" : "Approve the claim first");
        }
        String outcome = blank(str(in.get("outcome"))) ? "credit_note" : str(in.get("outcome"));
        if (!Set.of("credit_note", "refund", "replacement", "none").contains(outcome)) {
            throw ApiException.badRequest("outcome must be one of: credit_note, none, refund, replacement");
        }
        String orderId = str(r.get("orderId"));
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ?", orderId);
        List<LineInput> cnLines = new ArrayList<>();
        List<Map<String, Object>> cnLinks = new ArrayList<>();
        String invoiceId = null;
        List<Map<String, Object>> replacement = new ArrayList<>();
        for (Map<String, Object> l : db.rows("SELECT rl.*, ol.* , rl.id AS \"returnLineId\", rl.quantity AS \"claimQty\","
                + " ol.id AS \"lineId\" FROM \"SalesReturnLine\" rl JOIN \"SalesOrderLine\" ol ON ol.id = rl.\"orderLineId\""
                + " WHERE rl.\"returnId\" = ?", id)) {
            BigDecimal qty = physical ? dec(l.get("receivedQty")) : dec(l.get("claimQty"));
            if (qty.signum() <= 0) {
                continue;
            }
            if ("replacement".equals(outcome)) {
                replacement.add(Obj.of("productId", l.get("productId"), "description", "Replacement: " + l.get(
                        "description"), "quantity", qty, "uomId", l.get("uomId"), "unitPrice", 0, "priceSource", "manual"));
                continue;
            }
            if ("none".equals(outcome)) {
                continue;
            }
            BigDecimal net = dec(l.get("qtyInvoiced")).subtract(dec(l.get("qtyCredited")));
            BigDecimal credit = qty.min(net);
            BigDecimal uninvoiced = qty.subtract(credit);
            if (credit.signum() > 0) {
                cnLines.add(new LineInput(str(l.get("productId")), null, null, null, null, "Return " + r.get("number")
                        + ": " + l.get("description"), credit, dec(l.get("unitPrice")), dec(l.get("discountPercent")), null,
                        null, null, "manual", str(l.get("taxId")), null, Boolean.TRUE.equals(l.get("taxInclusive")),
                        str(l.get("uomId"))));
                cnLinks.add(Obj.of("lineId", l.get("lineId"), "qty", credit));
                if (invoiceId == null) {
                    Map<String, Object> inv = db.row("SELECT k.\"documentId\" FROM \"SalesInvoiceLink\" k JOIN \"Document\" d"
                            + " ON d.id = k.\"documentId\" WHERE k.\"orderLineId\" = ? AND k.kind = 'regular' AND"
                            + " d.status::text IN ('posted', 'paid') ORDER BY d.\"createdAt\" DESC LIMIT 1", l.get("lineId"));
                    invoiceId = inv == null ? null : str(inv.get("documentId"));
                }
                db.update("UPDATE \"SalesReturnLine\" SET \"creditQty\" = ? WHERE id = ?", credit, l.get("returnLineId"));
            }
            if (uninvoiced.signum() > 0) {
                // not invoiced yet: the returned quantity is no longer billable (recorded decision)
                BigDecimal before = dec(l.get("qtyBillable"));
                BigDecimal after = before.subtract(uninvoiced).max(dec(l.get("qtyInvoiced")));
                db.update("INSERT INTO \"SalesFulfilmentDecision\" (id, \"organizationId\", \"orderId\", \"orderLineId\","
                        + " basis, quantity, \"previousQty\", reason, \"decidedById\") VALUES (?, ?, ?, ?, 'manual', ?, ?, ?, ?)",
                        SalesDb.newId(), org(), orderId, l.get("lineId"), after, before, "Returned on " + r.get("number"),
                        user());
                db.update("UPDATE \"SalesOrderLine\" SET \"qtyBillable\" = ? WHERE id = ?", after, l.get("lineId"));
            }
        }
        String creditNoteId = null;
        String refundId = null;
        String replacementId = null;
        if (!cnLines.isEmpty()) {
            Map<String, Object> cn = creditNotes.create(new CreditNoteInput(str(r.get("partnerId")), LocalDate.now()
                    .toString(), null, str(r.get("number")), "Return " + r.get("number") + " of sales order " + o.get(
                            "number"), invoiceId, cnLines));
            creditNoteId = str(cn.get("id"));
            creditNotes.post(creditNoteId, "none");
            BigDecimal total = dec(creditNotes.findOne(creditNoteId).get("totalAmount"));
            for (Map<String, Object> k : cnLinks) {
                db.update("INSERT INTO \"SalesInvoiceLink\" (id, \"organizationId\", \"orderId\", \"orderLineId\","
                        + " \"documentId\", kind, quantity, amount) VALUES (?, ?, ?, ?, ?, 'credit_note', ?, 0)",
                        SalesDb.newId(), org(), orderId, k.get("lineId"), creditNoteId, k.get("qty"));
            }
            if ("refund".equals(outcome)) {
                refundId = str(payments.createCustomerRefund(new PaymentInput(str(r.get("partnerId")), LocalDate.now()
                        .toString(), total.setScale(2, RoundingMode.HALF_UP), blank(str(in.get("paymentMethod"))) ? "bank"
                                : str(in.get("paymentMethod")), str(in.get("accountId")), "Refund " + r.get("number"), null,
                        List.of(), null, true, null, null), true, true).get("id"));
            }
        }
        if (!replacement.isEmpty()) {
            Map<String, Object> rep = orders.create(Obj.of("partnerId", r.get("partnerId"), "locationId", o.get(
                    "locationId"), "siteId", o.get("siteId"), "notes", "Replacement for " + r.get("number"), "lines",
                    replacement));
            replacementId = str(rep.get("id"));
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'closed', outcome = ?, \"creditNoteId\" = ?, \"refundPaymentId\" = ?,"
                + " \"replacementOrderId\" = ?, \"closedAt\" = now(), \"updatedAt\" = now() WHERE id = ?", outcome,
                creditNoteId, refundId, replacementId, id);
        db.update("UPDATE \"SalesOrderLine\" l SET \"qtyCredited\" = COALESCE((SELECT sum(k.quantity) FROM"
                + " \"SalesInvoiceLink\" k JOIN \"Document\" d ON d.id = k.\"documentId\" WHERE k.\"orderLineId\" = l.id AND"
                + " k.kind = 'credit_note' AND d.status::text IN ('posted', 'paid')), 0) WHERE l.\"orderId\" = ?", orderId);
        if (r.get("complaintId") != null) {
            db.update("UPDATE \"CustomerComplaint\" SET status = 'resolved', resolution = ?, \"resolvedAt\" = now(),"
                    + " \"updatedAt\" = now() WHERE id = ?", "Return " + r.get("number") + " closed: " + outcome,
                    r.get("complaintId"));
        }
        orders.refreshStatus(orderId);
        events.publish(DomainEvents.SALES_RETURN_CLOSED, Obj.of("v", 1, "organizationId", org(), "returnId", id, "number",
                r.get("number"), "orderId", orderId, "outcome", outcome));
        audit.record(AuditInput.of("SalesReturn", id, "update", null, Obj.of("status", "closed", "outcome", outcome,
                "creditNoteId", creditNoteId)));
        Map<String, Object> out = get(id);
        out.put("creditAmount", creditNoteId == null ? 0 : JsNumbers.js(dec(creditNotes.findOne(creditNoteId).get(
                "totalAmount"))));
        return out;
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> r = lock(id, null);
        if (!Set.of("requested", "approved").contains(r.get("status"))) {
            throw ApiException.badRequest("Only a return not yet received can be cancelled");
        }
        db.update("UPDATE \"SalesReturn\" SET status = 'cancelled', \"updatedAt\" = now() WHERE id = ?", id);
        audit.record(AuditInput.of("SalesReturn", id, "cancel"));
        return get(id);
    }

    private void returnedProjection(String orderId) {
        db.update("UPDATE \"SalesOrderLine\" l SET \"qtyReturned\" = COALESCE((SELECT sum(rl.\"receivedQty\") FROM"
                + " \"SalesReturnLine\" rl JOIN \"SalesReturn\" r ON r.id = rl.\"returnId\" WHERE rl.\"orderLineId\" = l.id"
                + " AND r.status IN ('received', 'inspected', 'closed')), 0) WHERE l.\"orderId\" = ?", orderId);
    }

    private Map<String, Object> lock(String id, String expected) {
        Map<String, Object> r = db.row("SELECT * FROM \"SalesReturn\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE",
                id, org());
        if (r == null) {
            throw ApiException.notFound("Return not found");
        }
        orders.require(str(r.get("orderId")));
        if (expected != null && !expected.equals(r.get("status"))) {
            throw ApiException.badRequest("Return " + r.get("number") + " is " + r.get("status"));
        }
        return r;
    }
}
