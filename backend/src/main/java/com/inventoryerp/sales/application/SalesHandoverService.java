package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery &amp; fulfilment handover of a sales order or a sales invoice — the same model as a branch cash sale
 * ({@code CashSaleService#updateDelivery}): customer collects from a store / warehouse, or delivery to site, and the
 * status flow pending → scheduled → dispatched → delivered (or failed). Logistics only: stock moves stay with the
 * delivery orders and invoice posting. Additive extension beyond the NestJS reference.
 *
 * <p>The row is created lazily on the first update; until then {@link #get} returns defaults derived from the source
 * document. On a sales order the method and address are written through to the order header.
 */
@Service
public class SalesHandoverService {

    public static final String SALES_ORDER = "sales_order";
    public static final String INVOICE = "invoice";

    static final List<String> FIELDS = List.of("method", "status", "locationId", "address", "contactName",
            "contactPhone", "scheduledDate", "driver", "vehicle", "receivedBy", "notes", "landmark", "loadingSite",
            "deliveryScope", "offloadingEquipment");
    private static final List<String> STATUSES = List.of("pending", "scheduled", "dispatched", "delivered", "failed");
    private static final Set<String> RECEIPT_FIELDS = Set.of("receivedBy", "notes");

    private final SalesDb db;
    private final AuditService audit;

    public SalesHandoverService(SalesDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** The source document header the handover hangs off (org-scoped), with its stage. */
    private record Source(String type, String id, String stage, Map<String, Object> row) {}

    private Source source(String type, String id, boolean lock) {
        if (SALES_ORDER.equals(type)) {
            Map<String, Object> o = db.row("SELECT o.*, p.name AS \"partnerName\", p.phone AS \"partnerPhone\" FROM"
                    + " \"SalesOrder\" o LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\" WHERE o.id = ? AND"
                    + " o.\"organizationId\" = ?" + (lock ? " FOR UPDATE OF o" : ""), id, org());
            if (o == null) {
                throw ApiException.notFound("Sales order not found");
            }
            String st = str(o.get("status"));
            String stage = "cancelled".equals(st) ? "closed"
                    : List.of("confirmed", "in_fulfilment", "done").contains(st) ? "ready" : "plan";
            return new Source(type, id, stage, o);
        }
        if (INVOICE.equals(type)) {
            Map<String, Object> d = db.row("SELECT d.id, d.status::text AS status, d.\"deliveryAddress\","
                    + " d.\"deliveryDate\", d.\"branchId\", p.name AS \"partnerName\", p.phone AS \"partnerPhone\" FROM"
                    + " \"Document\" d LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.id = ? AND"
                    + " d.\"organizationId\" = ?" + (lock ? " FOR UPDATE OF d" : ""), id, org());
            if (d == null) {
                throw ApiException.notFound("Invoice not found");
            }
            String st = str(d.get("status"));
            String stage = List.of("cancelled", "rejected").contains(st) ? "closed"
                    : List.of("posted", "paid").contains(st) ? "ready" : "plan";
            return new Source(type, id, stage, d);
        }
        throw ApiException.badRequest("sourceType must be one of: sales_order, invoice");
    }

    private Map<String, Object> stored(Source s) {
        return db.row("SELECT * FROM \"SalesHandover\" WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND"
                + " \"sourceId\" = ?", org(), s.type(), s.id());
    }

    /** Handover values in wire form (dates as ISO strings), from the stored row or the source defaults. */
    private Map<String, Object> values(Source s, Map<String, Object> h) {
        Map<String, Object> v = new LinkedHashMap<>();
        if (h != null) {
            for (String k : FIELDS) {
                v.put(k, h.get(k) == null ? null : "scheduledDate".equals(k) ? h.get(k).toString().substring(0, 10)
                        : str(h.get(k)));
            }
            return v;
        }
        Map<String, Object> r = s.row();
        boolean order = SALES_ORDER.equals(s.type());
        String address = str(r.get("deliveryAddress"));
        v.put("method", order ? ("collect".equals(str(r.get("deliveryMethod"))) ? "pickup" : "delivery")
                : blank(address) ? "pickup" : "delivery");
        v.put("status", "pending");
        v.put("locationId", order ? str(r.get("locationId")) : null);
        v.put("address", blank(address) ? null : address);
        v.put("contactName", str(r.get("partnerName")));
        v.put("contactPhone", str(r.get("partnerPhone")));
        Object date = order ? r.get("requestedDate") : r.get("deliveryDate");
        v.put("scheduledDate", date == null ? null : date.toString().substring(0, 10));
        for (String k : List.of("driver", "vehicle", "receivedBy", "notes", "landmark", "loadingSite", "deliveryScope",
                "offloadingEquipment")) {
            v.put(k, null);
        }
        return v;
    }

    /** Delivery &amp; fulfilment view: handover values, stage of the source document and per-line quantities. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String type, String id) {
        Source s = source(type, id, false);
        return view(s, stored(s));
    }

    private Map<String, Object> view(Source s, Map<String, Object> h) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceType", s.type());
        out.put("sourceId", s.id());
        out.put("stage", s.stage());
        out.putAll(values(s, h));
        String loc = str(out.get("locationId"));
        Map<String, Object> l = loc == null ? null : db.row("SELECT name, code FROM \"InventoryLocation\" WHERE id = ?"
                + " AND \"organizationId\" = ?", loc, org());
        out.put("locationName", l == null ? null : str(l.get("name")));
        out.put("deliveredAt", h == null ? null : h.get("deliveredAt"));
        Map<String, Object> by = h == null || h.get("deliveredById") == null ? null : db.row("SELECT"
                + " COALESCE(NULLIF(trim(concat_ws(' ', \"firstName\", \"lastName\")), ''), email) AS name FROM \"User\""
                + " WHERE id = ?", h.get("deliveredById"));
        out.put("deliveredByName", by == null ? null : str(by.get("name")));
        out.put("saved", h != null);
        boolean done = "delivered".equals(out.get("status"));
        List<Map<String, Object>> lines = new ArrayList<>();
        if (SALES_ORDER.equals(s.type())) {
            for (Map<String, Object> r : db.rows("SELECT l.id, l.description, l.quantity, l.\"qtyCancelled\","
                    + " l.\"qtyDelivered\", pr.name AS \"productName\", u.code AS \"uomCode\" FROM \"SalesOrderLine\" l"
                    + " LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id ="
                    + " l.\"uomId\" WHERE l.\"orderId\" = ? ORDER BY l.\"sortOrder\"", s.id())) {
                BigDecimal ordered = dec(r.get("quantity")).subtract(dec(r.get("qtyCancelled")));
                lines.add(line(r, ordered, dec(r.get("qtyDelivered")), done));
            }
        } else {
            for (Map<String, Object> r : db.rows("SELECT l.id, l.description, l.quantity, pr.name AS \"productName\""
                    + " FROM \"DocumentLine\" l LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" WHERE"
                    + " l.\"documentId\" = ? AND l.\"organizationId\" = ? AND l.\"lineType\" = 'product' ORDER BY"
                    + " l.\"lineNumber\"", s.id(), org())) {
                lines.add(line(r, dec(r.get("quantity")), null, done));
            }
        }
        out.put("lines", lines);
        return out;
    }

    private static Map<String, Object> line(Map<String, Object> r, BigDecimal ordered, BigDecimal issued,
            boolean done) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("id", r.get("id"));
        l.put("productName", r.get("productName"));
        l.put("description", r.get("description"));
        l.put("uomCode", r.get("uomCode"));
        l.put("quantity", ordered);
        l.put("issuedQty", issued);
        l.put("handedOverQty", done ? ordered : BigDecimal.ZERO);
        return l;
    }

    /**
     * Update the handover. Editable unless the source is cancelled; dispatch and delivery need the source confirmed
     * (sales order) or posted (invoice); a completed handover keeps only the receiver and notes open.
     */
    @Transactional
    public Map<String, Object> update(String type, String id, Map<String, Object> in) {
        Set<String> unknown = new java.util.TreeSet<>(in.keySet());
        unknown.removeAll(FIELDS);
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("property " + unknown.iterator().next() + " should not exist");
        }
        Source s = source(type, id, true);
        if ("closed".equals(s.stage())) {
            throw ApiException.badRequest(SALES_ORDER.equals(type)
                    ? "Delivery cannot change on a cancelled sales order"
                    : "Delivery cannot change on a cancelled invoice");
        }
        Map<String, Object> h = stored(s);
        Map<String, Object> before = values(s, h);
        Map<String, Object> next = new LinkedHashMap<>(before);
        for (String k : in.keySet()) {
            String v = str(in.get(k));
            next.put(k, blank(v) ? null : v.trim());
        }
        String method = str(next.get("method"));
        String status = str(next.get("status"));
        if (!List.of("pickup", "delivery").contains(method)) {
            throw ApiException.badRequest("method must be one of: pickup, delivery");
        }
        if (!STATUSES.contains(status)) {
            throw ApiException.badRequest("status must be one of: " + String.join(", ", STATUSES));
        }
        if (next.get("deliveryScope") != null && !List.of("full", "partial").contains(next.get("deliveryScope"))) {
            throw ApiException.badRequest("deliveryScope must be one of: full, partial");
        }
        if (next.get("offloadingEquipment") != null && !List.of("yes", "no").contains(next.get("offloadingEquipment"))) {
            throw ApiException.badRequest("offloadingEquipment must be one of: yes, no");
        }
        LocalDate scheduled = null;
        if (next.get("scheduledDate") != null) {
            String d = str(next.get("scheduledDate"));
            try {
                scheduled = LocalDate.parse(d.substring(0, Math.min(10, d.length())));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("scheduledDate must be a valid ISO 8601 date string");
            }
            next.put("scheduledDate", scheduled.toString());
        }
        if (next.get("locationId") != null && db.count("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND"
                + " \"organizationId\" = ?", List.of(next.get("locationId"), org())) == 0) {
            throw ApiException.badRequest("Stock location not found");
        }
        if ("delivered".equals(before.get("status"))) {
            for (String k : FIELDS) {
                if (!RECEIPT_FIELDS.contains(k) && !Objects.equals(next.get(k), before.get(k))) {
                    throw ApiException.badRequest("Delivery is already completed; only the receiver and notes can change");
                }
            }
        }
        if (List.of("dispatched", "delivered").contains(status) && !"ready".equals(s.stage())) {
            throw ApiException.badRequest(SALES_ORDER.equals(type)
                    ? "Confirm the sales order before the goods are dispatched or delivered"
                    : "Post the invoice before the goods are dispatched or delivered");
        }
        if ("delivery".equals(method) && !"pending".equals(status) && next.get("address") == null) {
            throw ApiException.badRequest("address should not be empty for a delivery");
        }
        if ("pickup".equals(method) && !"pending".equals(status) && next.get("locationId") == null) {
            throw ApiException.badRequest("locationId should not be empty for a customer collection");
        }
        if ("scheduled".equals(status) && scheduled == null) {
            throw ApiException.badRequest("scheduledDate should not be empty when the delivery is scheduled");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> changed = new LinkedHashMap<>();
        for (String k : FIELDS) {
            Object v = "scheduledDate".equals(k) ? scheduled : next.get(k);
            data.put(k, v);
            if (!Objects.equals(before.get(k), next.get(k))) {
                changed.put(k, next.get(k));
            }
        }
        boolean nowDelivered = "delivered".equals(status) && !"delivered".equals(before.get("status"));
        data.put("updatedById", user());
        if (h == null) {
            data.put("sourceType", type);
            data.put("sourceId", id);
            h = db.insert("SalesHandover", data);
        } else {
            h = db.patch("SalesHandover", str(h.get("id")), data, true);
        }
        if (nowDelivered) {
            db.update("UPDATE \"SalesHandover\" SET \"deliveredAt\" = " + NOW + ", \"deliveredById\" = ? WHERE id = ?",
                    user(), h.get("id"));
        }
        if (SALES_ORDER.equals(type) && (changed.containsKey("method") || changed.containsKey("address"))) {
            db.update("UPDATE \"SalesOrder\" SET \"deliveryMethod\" = ?, \"deliveryAddress\" = ?, \"updatedAt\" = " + NOW
                    + " WHERE id = ? AND \"organizationId\" = ?", "pickup".equals(method) ? "collect" : "delivery",
                    next.get("address"), id, org());
        }
        if (!changed.isEmpty()) {
            audit.record(AuditInput.of(SALES_ORDER.equals(type) ? "SalesOrder" : "Document", id, "update", null,
                    Map.of("handover", changed)));
        }
        return view(s, stored(s));
    }
}
