package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Amendments of confirmed sales orders (ADR-S011 §7): a change request records the requested changes and their
 * impact, an approver applies it, and applying writes an immutable {@code SalesOrderRevision} (snapshot of the
 * previous state + field diff). Quantities never go below what is delivered, billable or invoiced; a price or
 * product change on a line that already moved is split (old line closed short, new line for the remainder) so
 * invoiced history is never rewritten.
 */
@Service
public class SalesOrderChangeService {

    private static final Set<String> HEADER_KEYS = Set.of("requestedDate", "commitmentDate", "siteId",
            "deliveryAddress", "paymentTermId", "notes", "customerRef", "deliveryMethod", "locationId");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SalesDb db;
    private final SalesOrderService orders;
    private final DocumentBuilderService builder;
    private final SequenceService sequence;
    private final AuditService audit;
    private final CreditService credit;
    private final SalesAtpService atp;

    public SalesOrderChangeService(SalesDb db, SalesOrderService orders, DocumentBuilderService builder,
            SequenceService sequence, AuditService audit, CreditService credit, SalesAtpService atp) {
        this.atp = atp;
        this.credit = credit;
        this.db = db;
        this.orders = orders;
        this.builder = builder;
        this.sequence = sequence;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String orderId) {
        orders.require(orderId);
        return db.rows("SELECT * FROM \"SalesOrderChangeRequest\" WHERE \"orderId\" = ? AND \"organizationId\" = ?"
                + " ORDER BY \"createdAt\" DESC", orderId, org());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> cr = require(id);
        orders.require(str(cr.get("orderId")));
        return cr;
    }

    /**
     * Request a change. {@code changes = {header: {...}, lines: [{lineId, quantity?, unitPrice?, discountPercent?,
     * promisedDate?, productId?}], addLines: [line]}}. Validated now; the impact (value, quantities, deliveries,
     * invoiced lines) is stored for the approver.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> request(String orderId, Map<String, Object> in) {
        Map<String, Object> o = orders.lock(orderId);
        if (!SalesOrderService.OPEN.contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders can be amended; edit a draft directly");
        }
        String reason = str(in.get("reason"));
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        if (db.count("SELECT count(*) FROM \"SalesOrderChangeRequest\" WHERE \"orderId\" = ? AND status ="
                + " 'pending_approval'", List.of(orderId)) > 0) {
            throw ApiException.conflict("Another change request is waiting for approval on this order");
        }
        Map<String, Object> changes = in.get("changes") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> impact = impact(o, changes);
        int year = LocalDate.now().getYear();
        String id = SalesDb.newId();
        String number = sequence.next("sales_order_change:" + year, "CR-" + year + "-", 5);
        db.update("INSERT INTO \"SalesOrderChangeRequest\" (id, \"organizationId\", \"orderId\", number, status, reason,"
                + " changes, impact, \"baseRevision\", \"requestedById\") VALUES (?, ?, ?, ?, 'pending_approval', ?,"
                + " ?::jsonb, ?::jsonb, ?, ?)", id, org(), orderId, number, reason, SalesDb.toJson(changes),
                SalesDb.toJson(impact), ((Number) o.get("revision")).intValue(), user());
        audit.record(AuditInput.of("SalesOrder", orderId, "update", null, Obj.of("changeRequest", number, "reason",
                reason)));
        return get(id);
    }

    /** Approve and apply (needs {@code sales:order:amend_approve}; not the requester unless {@code sales:configure}). */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> approve(String id, String comment) {
        Map<String, Object> cr = lockRequest(id);
        if (user() != null && user().equals(cr.get("requestedById"))
                && !TenantContext.has(Permissions.SALES__CONFIGURE)) {
            throw ApiException.forbidden("You cannot approve your own change request");
        }
        String orderId = str(cr.get("orderId"));
        Map<String, Object> o = orders.lock(orderId);
        if (((Number) o.get("revision")).intValue() != ((Number) cr.get("baseRevision")).intValue()) {
            throw ApiException.conflict("The order changed since this request; create a new change request");
        }
        if (!SalesOrderService.OPEN.contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders can be amended");
        }
        Map<String, Object> changes = cr.get("changes") instanceof Map<?, ?> m ? (Map<String, Object>) m
                : (Map<String, Object>) JSON.readValue(String.valueOf(cr.get("changes")), Map.class);
        impact(o, changes); // re-validate against the current quantities
        Map<String, Object> before = snapshot(orderId);
        apply(o, changes);
        int revision = ((Number) o.get("revision")).intValue() + 1;
        db.patch("SalesOrder", orderId, Obj.of("revision", revision, "version",
                ((Number) o.get("version")).intValue() + 1), true);
        orders.retotal(orderId);
        Map<String, Object> after = snapshot(orderId);
        db.update("INSERT INTO \"SalesOrderRevision\" (id, \"organizationId\", \"orderId\", revision, total, snapshot,"
                + " diff, reason, \"changeRequestId\", \"createdById\") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)",
                SalesDb.newId(), org(), orderId, revision - 1, dec(before.get("total")), SalesDb.toJson(before),
                SalesDb.toJson(diff(before, after)), cr.get("reason"), id, user());
        db.patch("SalesOrderChangeRequest", id, Obj.of("status", "applied", "decidedById", user(), "decidedAt",
                Instant.now(), "decisionComment", blank(comment) ? null : comment, "appliedRevision", revision), true);
        audit.record(AuditInput.of("SalesOrder", orderId, "update", before, Obj.of("changeRequest", cr.get("number"),
                "revision", revision)));
        orders.publish(DomainEvents.SALES_ORDER_AMENDED, orderId, Map.of("revision", revision, "changeRequestId", id));
        atp.reserveOrder(orderId);
        BigDecimal delta = dec(after.get("total")).subtract(dec(before.get("total")));
        if (delta.signum() > 0) {
            CreditService.Decision c = credit.check("amendment", str(o.get("partnerId")), "sales_order", orderId,
                    str(o.get("number")), delta, str(o.get("branchId")));
            if (!c.passed()) {
                db.patch("SalesOrder", orderId, Obj.of("onHold", true, "holdReason", String.join("; ", c.reasons()),
                        "holdKind", "blocked".equals(c.result()) ? "customer" : "credit"), true);
                orders.publish(DomainEvents.SALES_ORDER_HELD, orderId, Map.of("reason", String.join("; ", c.reasons())));
            }
        }
        orders.refreshStatus(orderId);
        return get(id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String comment) {
        Map<String, Object> cr = lockRequest(id);
        db.patch("SalesOrderChangeRequest", id, Obj.of("status", "rejected", "decidedById", user(), "decidedAt",
                Instant.now(), "decisionComment", blank(comment) ? "Rejected" : comment), true);
        audit.record(AuditInput.of("SalesOrder", str(cr.get("orderId")), "reject", null, Obj.of("changeRequest",
                cr.get("number"))));
        return get(id);
    }

    /** Requester withdraws a pending request. */
    @Transactional
    public Map<String, Object> withdraw(String id) {
        lockRequest(id);
        db.patch("SalesOrderChangeRequest", id, Obj.of("status", "cancelled", "decidedById", user(), "decidedAt",
                Instant.now()), true);
        return get(id);
    }

    // ------------------------------------------------------------------ impact / validation

    @SuppressWarnings("unchecked")
    private Map<String, Object> impact(Map<String, Object> o, Map<String, Object> changes) {
        String orderId = str(o.get("id"));
        Map<String, Object> header = changes.get("header") instanceof Map<?, ?> h ? (Map<String, Object>) h : Map.of();
        for (String k : header.keySet()) {
            if (!HEADER_KEYS.contains(k)) {
                throw ApiException.badRequest("Header field " + k + " cannot be changed by a change request");
            }
        }
        if (header.containsKey("deliveryMethod") && !Set.of("delivery", "collect").contains(str(header.get(
                "deliveryMethod")))) {
            throw ApiException.badRequest("deliveryMethod must be one of: collect, delivery");
        }
        List<Map<String, Object>> lineChanges = changes.get("lines") instanceof List<?> l ? (List<Map<String, Object>>) l
                : List.of();
        List<Map<String, Object>> add = changes.get("addLines") instanceof List<?> l ? (List<Map<String, Object>>) l
                : List.of();
        if (header.isEmpty() && lineChanges.isEmpty() && add.isEmpty()) {
            throw ApiException.badRequest("The change request changes nothing");
        }
        Map<String, Map<String, Object>> current = new HashMap<>();
        for (Map<String, Object> l : db.rows("SELECT * FROM \"SalesOrderLine\" WHERE \"orderId\" = ?", orderId)) {
            current.put(str(l.get("id")), l);
        }
        BigDecimal valueBefore = dec(o.get("subtotal"));
        BigDecimal valueAfter = valueBefore;
        Map<String, BigDecimal> qtyDelta = new LinkedHashMap<>();
        List<String> invoicedLines = new ArrayList<>();
        List<String> splits = new ArrayList<>();
        for (Map<String, Object> c : lineChanges) {
            Map<String, Object> l = current.get(str(c.get("lineId")));
            if (l == null) {
                throw ApiException.notFound("Order line not found");
            }
            if (l.get("closedShortAt") != null) {
                throw ApiException.badRequest("Line " + l.get("description") + " is closed short");
            }
            BigDecimal floor = SalesOrderService.committedFloor(l);
            BigDecimal qty = c.get("quantity") == null ? dec(l.get("quantity")) : dec(c.get("quantity"));
            if (qty.compareTo(floor) < 0 || qty.signum() <= 0) {
                throw ApiException.badRequest("Quantity of " + l.get("description") + " cannot go below "
                        + floor.stripTrailingZeros().toPlainString() + " (already delivered or invoiced)");
            }
            BigDecimal price = c.get("unitPrice") == null ? dec(l.get("unitPrice")) : dec(c.get("unitPrice"));
            BigDecimal disc = c.get("discountPercent") == null ? dec(l.get("discountPercent"))
                    : dec(c.get("discountPercent"));
            if (price.signum() < 0 || disc.signum() < 0 || disc.compareTo(SalesOrderService.HUNDRED) > 0) {
                throw ApiException.badRequest("Invalid price or discount for " + l.get("description"));
            }
            boolean commercial = c.get("unitPrice") != null || c.get("discountPercent") != null
                    || (c.get("productId") != null && !str(c.get("productId")).equals(str(l.get("productId"))));
            if (commercial && floor.signum() > 0) {
                splits.add(str(l.get("id")));
            }
            if (dec(l.get("qtyInvoiced")).signum() > 0) {
                invoicedLines.add(str(l.get("id")));
            }
            BigDecimal oldValue = net(dec(l.get("quantity")), dec(l.get("unitPrice")), dec(l.get("discountPercent")));
            valueAfter = valueAfter.subtract(oldValue).add(net(qty, price, disc));
            String product = str(c.get("productId")) == null ? str(l.get("productId")) : str(c.get("productId"));
            if (product != null) {
                qtyDelta.merge(product, qty.subtract(dec(l.get("quantity"))), BigDecimal::add);
            }
        }
        for (Map<String, Object> a : add) {
            Map<String, Object> m = orders.line(str(o.get("partnerId")), str(o.get("agreementId")), LocalDate.now(), a, str(o.get("locationId")),
                    SalesOrderService.PriceScope.of(o));
            valueAfter = valueAfter.add(net(dec(m.get("quantity")), dec(m.get("unitPrice")), dec(m.get(
                    "discountPercent"))));
            if (m.get("productId") != null) {
                qtyDelta.merge(str(m.get("productId")), dec(m.get("quantity")), BigDecimal::add);
            }
        }
        long openDeliveries = db.count("SELECT count(*) FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'sales_order' AND \"sourceId\" = ? AND status NOT IN ('cancelled', 'closed',"
                + " 'delivered')", List.of(org(), orderId));
        Map<String, Object> qty = new LinkedHashMap<>();
        qtyDelta.forEach((k, v) -> {
            if (v.signum() != 0) {
                qty.put(k, JsNumbers.js(v));
            }
        });
        return Obj.of("valueBefore", JsNumbers.js(valueBefore), "valueAfter", JsNumbers.js(valueAfter), "valueDelta",
                JsNumbers.js(valueAfter.subtract(valueBefore)), "quantityDeltaByProduct", qty, "reservationNote",
                qty.isEmpty() ? "no reservation change" : "reservations follow the quantity change (phase S4)",
                "openDeliveries", openDeliveries, "invoicedLines", invoicedLines, "splitLines", splits);
    }

    private static BigDecimal net(BigDecimal qty, BigDecimal price, BigDecimal disc) {
        return qty.multiply(price).multiply(BigDecimal.ONE.subtract(disc.divide(SalesOrderService.HUNDRED, 10,
                java.math.RoundingMode.HALF_UP))).setScale(6, java.math.RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ apply

    @SuppressWarnings("unchecked")
    private void apply(Map<String, Object> o, Map<String, Object> changes) {
        String orderId = str(o.get("id"));
        Map<String, Object> header = changes.get("header") instanceof Map<?, ?> h ? (Map<String, Object>) h : Map.of();
        if (!header.isEmpty()) {
            Map<String, Object> d = new LinkedHashMap<>();
            header.forEach((k, v) -> d.put(k, k.endsWith("Date") ? SalesDb.date(str(v)) : blank(str(v)) ? null : str(v)));
            db.patch("SalesOrder", orderId, d, true);
        }
        int sort = ((Number) db.row("SELECT COALESCE(max(\"sortOrder\"), -1) + 1 AS n FROM \"SalesOrderLine\" WHERE"
                + " \"orderId\" = ?", orderId).get("n")).intValue();
        List<Map<String, Object>> newLines = new ArrayList<>();
        List<Map<String, Object>> lineChanges = changes.get("lines") instanceof List<?> l ? (List<Map<String, Object>>) l
                : List.of();
        for (Map<String, Object> c : lineChanges) {
            Map<String, Object> l = db.row("SELECT * FROM \"SalesOrderLine\" WHERE id = ? AND \"orderId\" = ? FOR UPDATE",
                    str(c.get("lineId")), orderId);
            BigDecimal floor = SalesOrderService.committedFloor(l);
            BigDecimal qty = c.get("quantity") == null ? dec(l.get("quantity")) : dec(c.get("quantity"));
            boolean productChange = c.get("productId") != null && !str(c.get("productId")).equals(str(l.get("productId")));
            boolean commercial = c.get("unitPrice") != null || c.get("discountPercent") != null || productChange;
            if (commercial && floor.signum() > 0) {
                // Split: the moved part stays at the old terms, the remainder becomes a new line.
                db.update("UPDATE \"SalesOrderLine\" SET \"qtyCancelled\" = quantity - ?, \"closedShortAt\" ="
                        + " date_trunc('milliseconds', clock_timestamp())::timestamp(3), \"closeShortReason\" = ? WHERE id ="
                        + " ?", floor, "Split by change request", l.get("id"));
                BigDecimal rest = qty.subtract(floor);
                if (rest.signum() > 0) {
                    newLines.add(replacement(l, c, rest));
                }
                continue;
            }
            if (commercial || c.get("quantity") != null) {
                Map<String, Object> m = replacement(l, c, qty);
                List<LineInput> in = List.of(new LineInput(str(m.get("productId")), null, null, null, null,
                        str(m.get("description")), qty, dec(m.get("unitPrice")), dec(m.get("discountPercent")), null, null,
                        null, "manual", str(m.get("taxId")), null, Boolean.TRUE.equals(m.get("taxInclusive")),
                        str(m.get("uomId"))));
                var p = builder.prepareLines(in).prepared().getFirst();
                db.update("UPDATE \"SalesOrderLine\" SET \"productId\" = ?, description = ?, quantity = ?, \"unitPrice\" ="
                        + " ?, \"discountPercent\" = ?, \"priceSource\" = ?, \"lineSubtotal\" = ?, \"lineTax\" = ?,"
                        + " \"lineTotal\" = ? WHERE id = ?", p.productId(), p.description(), qty, p.unitPrice(),
                        p.discountPercent(), m.get("priceSource"), p.subtotal(), p.taxAmount(), p.total(), l.get("id"));
                if (qty.compareTo(dec(l.get("quantity"))) != 0) {
                    // ADR-S066: a changed quantity no longer matches the typed size
                    db.update("UPDATE \"SalesOrderLine\" SET pieces = NULL, length = NULL, width = NULL, height = NULL"
                            + " WHERE id = ?", l.get("id"));
                }
            }
            if (c.containsKey("promisedDate")) {
                db.update("UPDATE \"SalesOrderLine\" SET \"promisedDate\" = ? WHERE id = ?",
                        SalesDb.date(str(c.get("promisedDate"))), l.get("id"));
            }
        }
        List<Map<String, Object>> add = changes.get("addLines") instanceof List<?> l ? (List<Map<String, Object>>) l
                : List.of();
        for (Map<String, Object> a : add) {
            newLines.add(orders.line(str(o.get("partnerId")), str(o.get("agreementId")), LocalDate.now(), a, str(o.get("locationId")),
                    SalesOrderService.PriceScope.of(o)));
        }
        orders.insertLines(orderId, newLines, sort);
    }

    private static Map<String, Object> replacement(Map<String, Object> l, Map<String, Object> c, BigDecimal qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean commercial = c.get("unitPrice") != null || c.get("discountPercent") != null;
        m.put("productId", c.get("productId") != null ? str(c.get("productId")) : str(l.get("productId")));
        m.put("description", c.get("description") != null ? str(c.get("description")) : str(l.get("description")));
        m.put("quantity", qty);
        m.put("uomId", l.get("uomId"));
        m.put("listPrice", dec(l.get("listPrice")));
        m.put("unitPrice", c.get("unitPrice") == null ? dec(l.get("unitPrice")) : dec(c.get("unitPrice")));
        m.put("discountPercent", c.get("discountPercent") == null ? dec(l.get("discountPercent"))
                : dec(c.get("discountPercent")));
        m.put("priceSource", commercial ? "manual" : str(l.get("priceSource")));
        m.put("taxId", l.get("taxId"));
        m.put("taxInclusive", Boolean.TRUE.equals(l.get("taxInclusive")));
        m.put("promisedDate", c.containsKey("promisedDate") ? SalesDb.date(str(c.get("promisedDate")))
                : l.get("promisedDate"));
        m.put("sourceLocationId", l.get("sourceLocationId"));
        m.put("tolerancePct", dec(l.get("tolerancePct")));
        m.put("quotationLineId", null);
        return m;
    }

    private Map<String, Object> snapshot(String orderId) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ?", orderId);
        o.put("lines", db.rows("SELECT * FROM \"SalesOrderLine\" WHERE \"orderId\" = ? ORDER BY \"sortOrder\"", orderId));
        return o;
    }

    /** Field-level diff of header and lines (by id) between two snapshots. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> header = new LinkedHashMap<>();
        for (String k : after.keySet()) {
            if (Set.of("lines", "updatedAt", "version", "revision").contains(k)) {
                continue;
            }
            if (!String.valueOf(before.get(k)).equals(String.valueOf(after.get(k)))) {
                header.put(k, Obj.of("from", before.get(k), "to", after.get(k)));
            }
        }
        Map<String, Map<String, Object>> old = new HashMap<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) before.get("lines")) {
            old.put(str(l.get("id")), l);
        }
        List<Object> lines = new ArrayList<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) after.get("lines")) {
            Map<String, Object> was = old.get(str(l.get("id")));
            if (was == null) {
                lines.add(Obj.of("lineId", l.get("id"), "added", true, "description", l.get("description"), "quantity",
                        l.get("quantity"), "unitPrice", l.get("unitPrice")));
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            for (String k : List.of("productId", "quantity", "unitPrice", "discountPercent", "promisedDate",
                    "qtyCancelled")) {
                if (!String.valueOf(was.get(k)).equals(String.valueOf(l.get(k)))) {
                    f.put(k, Obj.of("from", was.get(k), "to", l.get(k)));
                }
            }
            if (!f.isEmpty()) {
                f.put("lineId", l.get("id"));
                lines.add(f);
            }
        }
        return Obj.of("header", header, "lines", lines);
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> cr = db.row("SELECT * FROM \"SalesOrderChangeRequest\" WHERE id = ? AND \"organizationId\""
                + " = ?", id, org());
        if (cr == null) {
            throw ApiException.notFound("Change request not found");
        }
        return cr;
    }

    private Map<String, Object> lockRequest(String id) {
        Map<String, Object> cr = require(id);
        orders.require(str(cr.get("orderId")));
        cr = db.row("SELECT * FROM \"SalesOrderChangeRequest\" WHERE id = ? FOR UPDATE", id);
        if (!"pending_approval".equals(cr.get("status"))) {
            throw ApiException.badRequest("Change request is not awaiting approval");
        }
        return cr;
    }
}
