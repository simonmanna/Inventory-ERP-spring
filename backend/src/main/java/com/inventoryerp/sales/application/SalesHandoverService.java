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
            "deliveryScope", "offloadingEquipment", "gpsCoordinates", "offloadingSite", "destinationLocationId",
            "courierName", "deadline");
    /** Date-only fields: stored as DATE, sent as {@code yyyy-MM-dd}. */
    private static final Set<String> DATES = Set.of("scheduledDate", "deadline");
    static final List<String> METHODS = List.of("pickup", "delivery", "transfer", "courier");
    private static final List<String> STATUSES = List.of("pending", "scheduled", "dispatched", "delivered", "failed");
    private static final Set<String> RECEIPT_FIELDS = Set.of("receivedBy", "notes");

    private final SalesDb db;
    private final AuditService audit;
    private final SalesLetterhead letterhead;

    public SalesHandoverService(SalesDb db, AuditService audit, SalesLetterhead letterhead) {
        this.db = db;
        this.audit = audit;
        this.letterhead = letterhead;
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
                    + " d.\"deliveryDate\", d.\"branchId\", d.\"documentNumber\" AS number, p.name AS \"partnerName\","
                    + " p.phone AS \"partnerPhone\", c.id AS \"cashSaleId\", c.number AS \"cashSaleNumber\" FROM"
                    + " \"Document\" d LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\" LEFT JOIN \"SalesSubmission\" c ON"
                    + " d.\"sourceType\" = 'cash_sale' AND c.id = d.\"sourceId\" AND c.\"organizationId\" = d.\"organizationId\""
                    + " WHERE d.id = ? AND"
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
                v.put(k, h.get(k) == null ? null : DATES.contains(k) ? h.get(k).toString().substring(0, 10)
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
                "offloadingEquipment", "gpsCoordinates", "offloadingSite", "destinationLocationId", "courierName")) {
            v.put(k, null);
        }
        Object deadline = order ? r.get("commitmentDate") : null;
        v.put("deadline", deadline == null ? null : deadline.toString().substring(0, 10));
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
        String dest = str(out.get("destinationLocationId"));
        Map<String, Object> dl = dest == null ? null : db.row("SELECT name FROM \"InventoryLocation\" WHERE id = ? AND"
                + " \"organizationId\" = ?", dest, org());
        out.put("destinationLocationName", dl == null ? null : str(dl.get("name")));
        out.put("deliveredAt", h == null ? null : h.get("deliveredAt"));
        Map<String, Object> by = h == null || h.get("deliveredById") == null ? null : db.row("SELECT"
                + " COALESCE(NULLIF(trim(concat_ws(' ', \"firstName\", \"lastName\")), ''), email) AS name FROM \"User\""
                + " WHERE id = ?", h.get("deliveredById"));
        out.put("deliveredByName", by == null ? null : str(by.get("name")));
        out.put("saved", h != null);
        String status = str(out.get("status"));
        boolean done = "delivered".equals(status);
        boolean picked = done || "dispatched".equals(status);
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> returns = new ArrayList<>();
        if (SALES_ORDER.equals(s.type())) {
            for (Map<String, Object> r : db.rows("SELECT l.id, l.\"productId\", l.description, l.quantity,"
                    + " l.\"qtyCancelled\", l.\"qtyDelivered\", GREATEST(l.\"qtyLoaded\", l.\"qtyDispatched\") AS picked,"
                    + " l.\"qtyInvoiced\" - l.\"qtyCredited\" AS invoiced, l.\"qtyReturned\" AS returned,"
                    + " pr.name AS \"productName\", u.code AS \"uomCode\" FROM \"SalesOrderLine\" l"
                    + " LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id ="
                    + " l.\"uomId\" WHERE l.\"orderId\" = ? ORDER BY l.\"sortOrder\"", s.id())) {
                BigDecimal ordered = dec(r.get("quantity")).subtract(dec(r.get("qtyCancelled")));
                Map<String, Object> ln = line(r, ordered, dec(r.get("qtyDelivered")), done);
                ln.put("pickedQty", dec(r.get("picked")));
                ln.put("invoicedQty", dec(r.get("invoiced")));
                ln.put("returnedQty", dec(r.get("returned")));
                lines.add(ln);
            }
            out.put("deliveryNoteNumber", null);
        } else {
            for (Map<String, Object> r : db.rows("SELECT l.id, l.\"productId\", l.description, l.quantity,"
                    + " pr.name AS \"productName\", u.code AS \"uomCode\" FROM \"DocumentLine\" l LEFT JOIN \"Product\" pr"
                    + " ON pr.id = l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE"
                    + " l.\"documentId\" = ? AND l.\"organizationId\" = ? AND l.\"lineType\" = 'product' ORDER BY"
                    + " l.\"lineNumber\"", s.id(), org())) {
                BigDecimal ordered = dec(r.get("quantity"));
                Map<String, Object> ln = line(r, ordered, null, done);
                ln.put("pickedQty", picked ? ordered : BigDecimal.ZERO);
                ln.put("invoicedQty", ordered);
                lines.add(ln);
            }
            returns = SalesDeliveryReturns.creditNotes(db, s.id());
            SalesDeliveryReturns.allocate(lines, returns, "quantity");
            out.put("deliveryNoteNumber", noteNumber(s));
        }
        out.put("sourceNumber", s.row().get("number"));
        if (INVOICE.equals(s.type())) {
            // a branch cash sale's invoice mirrors the sale's delivery; it is edited on the sale
            out.put("cashSaleId", s.row().get("cashSaleId"));
            out.put("cashSaleNumber", s.row().get("cashSaleNumber"));
        }
        for (Map<String, Object> ln : lines) {
            ln.remove("productId");
        }
        out.put("lines", lines);
        out.put("returns", returns);
        return out;
    }

    private static Map<String, Object> line(Map<String, Object> r, BigDecimal ordered, BigDecimal issued,
            boolean done) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("id", r.get("id"));
        l.put("productId", r.get("productId"));
        l.put("productName", r.get("productName"));
        l.put("description", r.get("description"));
        l.put("uomCode", r.get("uomCode"));
        l.put("quantity", ordered);
        l.put("issuedQty", issued);
        l.put("handedOverQty", done ? ordered : BigDecimal.ZERO);
        return l;
    }

    /** Delivery note number of an invoice handover: {@code DN/<invoice number>}. */
    private static String noteNumber(Source s) {
        return "DN/" + Objects.requireNonNullElse(str(s.row().get("number")), s.id());
    }

    /**
     * Printable delivery note of an invoice handover (sales orders print one per delivery trip instead). Available as
     * soon as the invoice exists, so it can travel with the goods; the receipt shows once the handover is delivered.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public SalesDeliveryNotePdf.Pdf note(String type, String id) {
        if (!INVOICE.equals(type)) {
            throw ApiException.badRequest("Sales order deliveries print their delivery note per delivery");
        }
        Source s = source(type, id, false);
        Map<String, Object> v = view(s, stored(s));
        boolean done = "delivered".equals(v.get("status"));
        List<SalesDeliveryNotePdf.Line> lines = new ArrayList<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) v.get("lines")) {
            lines.add(new SalesDeliveryNotePdf.Line(Objects.requireNonNullElse(str(l.get("productName")),
                    str(l.get("description"))), str(l.get("uomCode")), dec(l.get("quantity")), dec(l.get("handedOverQty")),
                    dec(l.get("returnedQty"))));
        }
        String method = str(v.get("method"));
        String contact = String.join(" · ", java.util.stream.Stream.of(str(v.get("contactName")),
                str(v.get("contactPhone"))).filter(x -> !blank(x)).toList());
        Object shipped = done ? v.get("deliveredAt") : null;
        return SalesDeliveryNotePdf.render(new SalesDeliveryNotePdf.Note(letterhead.current(), str(v.get("deliveryNoteNumber")),
                "Invoice", str(s.row().get("number")), str(s.row().get("partnerName")),
                "pickup".equals(method) ? null : str(v.get("address")), blank(contact) ? null : contact,
                METHOD_LABELS.get(method), shipped == null ? null : shipped.toString().substring(0, 10),
                str(v.get("scheduledDate")), str(v.get("deadline")), str(v.get("driver")), str(v.get("vehicle")),
                done ? str(v.get("receivedBy")) : null, shipped == null ? null : shipped.toString().substring(0, 10),
                str(v.get("notes")), lines));
    }

    static final Map<String, String> METHOD_LABELS = Map.of("pickup", "Customer collect", "delivery",
            "Delivery to site", "transfer", "Transfer to warehouse", "courier", "Courier / logistics");

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
        if (s.row().get("cashSaleId") != null) {
            throw ApiException.badRequest("The delivery of this invoice is managed on cash sale "
                    + s.row().get("cashSaleNumber"));
        }
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
        // Sales readiness R4d (DEL-6): once an order has deliveries they are the record of what left and arrived; the
        // free handover status may then not claim a dispatch or delivery of its own (the plan fields stay editable).
        if (SALES_ORDER.equals(type) && !java.util.Objects.equals(status, before.get("status"))
                && Set.of("dispatched", "delivered").contains(status)
                && (db.count("SELECT count(*) FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                        + " 'sales_order' AND \"sourceId\" = ? AND status <> 'cancelled'", List.of(org(), id)) > 0
                    || db.count("SELECT count(*) FROM \"SalesOrderLine\" l JOIN \"Product\" p ON p.id = l.\"productId\""
                            + " WHERE l.\"orderId\" = ? AND l.\"organizationId\" = ? AND p.\"trackInventory\"",
                            List.of(id, org())) > 0)) {
            // Stocked goods leave only through a delivery, which issues them from stock and books their cost.
            throw ApiException.badRequest("Stocked goods on this order leave through deliveries: dispatch and confirm"
                    + " them on the Delivery tab instead of marking the handover " + status);
        }
        if (!METHODS.contains(method)) {
            throw ApiException.badRequest("method must be one of: " + String.join(", ", METHODS));
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
        LocalDate scheduled = date(next, "scheduledDate");
        LocalDate deadline = date(next, "deadline");
        if (next.get("locationId") != null && db.count("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND"
                + " \"organizationId\" = ?", List.of(next.get("locationId"), org())) == 0) {
            throw ApiException.badRequest("Stock location not found");
        }
        if (next.get("destinationLocationId") != null && db.count("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ?"
                + " AND \"organizationId\" = ?", List.of(next.get("destinationLocationId"), org())) == 0) {
            throw ApiException.badRequest("Destination warehouse not found");
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
            Object v = "scheduledDate".equals(k) ? scheduled : "deadline".equals(k) ? deadline : next.get(k);
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
            db.update("UPDATE \"SalesHandover\" SET \"deliveredAt\" = " + NOW + ", \"deliveredById\" = ? WHERE id = ?"
                    + " AND \"organizationId\" = ?",
                    user(), h.get("id"), org());
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

    /**
     * Copy a branch cash sale's delivery onto its head-office invoice (insert or overwrite the invoice handover). The
     * sale already validated these values and stays the place they are edited, so they are written as they are, with
     * the sale's own delivered-at / delivered-by. A cancelled invoice is left alone.
     */
    @Transactional
    public void mirrorCashSale(String invoiceId, Map<String, Object> sale) {
        Source s = source(INVOICE, invoiceId, true);
        if ("closed".equals(s.stage())) {
            return;
        }
        String method = Objects.requireNonNullElse(firstText(sale.get("deliveryMethod")), "pickup");
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : FIELDS) {
            data.put(k, null);
        }
        data.put("method", method);
        data.put("status", Objects.requireNonNullElse(firstText(sale.get("deliveryStatus")), "pending"));
        data.put("locationId", str(sale.get("locationId")));
        data.put("address", str(sale.get("deliveryAddress")));
        data.put("contactName", firstText(sale.get("deliveryContactName"), sale.get("customerName"),
                s.row().get("partnerName")));
        data.put("contactPhone", firstText(sale.get("deliveryContactPhone"), sale.get("customerPhone"),
                s.row().get("partnerPhone")));
        data.put("scheduledDate", day(sale.get("deliveryScheduledDate")));
        data.put("deadline", day(sale.get("deliveryDeadline")));
        data.put("driver", str(sale.get("deliveryDriver")));
        data.put("vehicle", str(sale.get("deliveryVehicle")));
        data.put("receivedBy", str(sale.get("deliveryReceivedBy")));
        data.put("notes", str(sale.get("deliveryNotes")));
        data.put("landmark", str(sale.get("deliveryLandmark")));
        data.put("loadingSite", str(sale.get("deliveryLoadingSite")));
        data.put("deliveryScope", str(sale.get("deliveryScope")));
        data.put("offloadingEquipment", str(sale.get("deliveryOffloadingEquipment")));
        boolean delivered = "delivered".equals(data.get("status"));
        data.put("deliveredAt", delivered ? sale.get("deliveredAt") : null);
        data.put("deliveredById", delivered ? str(sale.get("deliveredById")) : null);
        data.put("updatedById", user());
        Map<String, Object> h = stored(s);
        if (h == null) {
            data.put("sourceType", INVOICE);
            data.put("sourceId", invoiceId);
            db.insert("SalesHandover", data);
        } else {
            db.patch("SalesHandover", str(h.get("id")), data, true);
        }
    }

    /** The first non-blank value as text, or null. */
    private static String firstText(Object... values) {
        for (Object v : values) {
            if (!blank(str(v))) {
                return str(v);
            }
        }
        return null;
    }

    private static LocalDate day(Object v) {
        return v == null ? null : v instanceof LocalDate d ? d : LocalDate.parse(v.toString().substring(0, 10));
    }

    /** Parse the date field {@code k} of {@code next} (normalising it to {@code yyyy-MM-dd}); null when empty. */
    private static LocalDate date(Map<String, Object> next, String k) {
        if (next.get(k) == null) {
            return null;
        }
        String d = str(next.get(k));
        try {
            LocalDate v = LocalDate.parse(d.substring(0, Math.min(10, d.length())));
            next.put(k, v.toString());
            return v;
        } catch (RuntimeException e) {
            throw ApiException.badRequest(k + " must be a valid ISO 8601 date string");
        }
    }
}
