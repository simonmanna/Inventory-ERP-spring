package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.fleet.application.DispatchCommands.FromSourceInput;
import com.inventoryerp.fleet.application.DispatchCommands.LoadInput;
import com.inventoryerp.fleet.application.DispatchCommands.LoadLineInput;
import com.inventoryerp.fleet.application.DispatchCommands.LoadedLineInput;
import com.inventoryerp.fleet.application.DispatchCommands.PodInput;
import com.inventoryerp.fleet.application.DispatchCommands.PodLineInput;
import com.inventoryerp.fleet.application.FleetDeliveryNoteService;
import com.inventoryerp.fleet.application.FleetDeliveryOrderService;
import com.inventoryerp.fleet.application.FleetDispatchService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales deliveries (SG-9): always on, whatever the Fleet switch says. A delivery is the same delivery order and load the
 * Fleet module uses, so stock, costing and billing run through one path; here the vehicle and driver are typed in instead
 * of chosen from fleet records. Damaged and rejected quantities and several photos extend the proof of delivery.
 */
@Service
public class SalesDeliveryService {

    private static final String LIST = """
            SELECT t.id, t.code, t.status, t.carrier, t."transporterPlate" AS "vehiclePlate", t."transporterDriverName" AS "driverName",
                   t."plannedDate", t."dispatchedAt", t."deliveredAt", t."deliveryNoteNumber", t."approvalRequired", t."approvedAt",
                   t."manualGross", t."manualTare", t."cancelReason", t."returnReason", d.id AS "deliveryOrderId", d.code AS "deliveryOrderCode",
                   d."sourceId" AS "orderId", d."sourceNumber" AS "orderNumber", d."branchId", p.name AS "partnerName",
                   COALESCE((SELECT sum(l."dispatchedQty") FROM "FleetLoadLine" l WHERE l."loadId" = t.id), 0) AS "dispatchedQty",
                   COALESCE((SELECT sum(l."deliveredQty") FROM "FleetLoadLine" l WHERE l."loadId" = t.id), 0) AS "deliveredQty",
                   COALESCE((SELECT sum(l."damagedQty") FROM "FleetLoadLine" l WHERE l."loadId" = t.id), 0) AS "damagedQty",
                   COALESCE((SELECT sum(l."rejectedQty") FROM "FleetLoadLine" l WHERE l."loadId" = t.id), 0) AS "rejectedQty"
              FROM "FleetLoad" t
              JOIN "FleetDeliveryOrder" d ON d.id = t."deliveryOrderId"
              LEFT JOIN "Partner" p ON p.id = d."partnerId"
            """;

    private final SalesDb db;
    private final SalesOrderService orders;
    private final SalesFulfilmentService fulfilment;
    private final SalesSettingsService settings;
    private final SalesAccess access;
    private final FleetDeliveryOrderService deliveryOrders;
    private final FleetDispatchService dispatch;
    private final FleetDeliveryNoteService notes;
    private final FilesService files;
    private final AuditService audit;

    public SalesDeliveryService(SalesDb db, SalesOrderService orders, SalesFulfilmentService fulfilment,
            SalesSettingsService settings, SalesAccess access, FleetDeliveryOrderService deliveryOrders,
            FleetDispatchService dispatch, FleetDeliveryNoteService notes, FilesService files, AuditService audit) {
        this.db = db;
        this.orders = orders;
        this.fulfilment = fulfilment;
        this.settings = settings;
        this.access = access;
        this.deliveryOrders = deliveryOrders;
        this.dispatch = dispatch;
        this.notes = notes;
        this.files = files;
        this.audit = audit;
    }

    /** Deliveries of sales orders. Filters: orderId, status (csv), failed, from, to. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Map<String, String> q) {
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ? AND d.\"sourceType\" = 'sales_order'");
        List<Object> params = new ArrayList<>(List.of(org()));
        where.append(access.branchClause("d.\"branchId\"", params));
        if (!blank(q.get("orderId"))) {
            where.append(" AND d.\"sourceId\" = ?");
            params.add(q.get("orderId"));
        }
        if (!blank(q.get("status"))) {
            List<String> st = List.of(q.get("status").split(","));
            where.append(" AND t.status IN (").append(SalesDb.marks(st.size())).append(')');
            params.addAll(st);
        }
        if ("true".equals(q.get("failed"))) {
            where.append(" AND (t.status IN ('cancelled', 'returned') OR EXISTS (SELECT 1 FROM \"FleetLoadLine\" l WHERE"
                    + " l.\"loadId\" = t.id AND (l.\"damagedQty\" > 0 OR l.\"rejectedQty\" > 0 OR l.\"returnedQty\" > 0)))");
        }
        if (!blank(q.get("from"))) {
            where.append(" AND t.\"createdAt\" >= ?");
            params.add(SalesDb.date(q.get("from")));
        }
        if (!blank(q.get("to"))) {
            where.append(" AND t.\"createdAt\" < ?::date + 1");
            params.add(SalesDb.date(q.get("to")));
        }
        return db.rows(LIST + where + " ORDER BY t.\"createdAt\" DESC LIMIT 500", params);
    }

    private Map<String, Object> require(String id) {
        List<Object> params = new ArrayList<>(List.of(id, org()));
        String scope = access.branchClause("d.\"branchId\"", params);
        Map<String, Object> t = db.row(LIST + " WHERE t.id = ? AND t.\"organizationId\" = ? AND d.\"sourceType\" = 'sales_order'"
                + scope, params.toArray());
        if (t == null) {
            throw ApiException.notFound("Delivery not found");
        }
        return t;
    }

    /** One delivery: lines with damaged and rejected quantities, proof of delivery, photos and discrepancies. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> t = new LinkedHashMap<>(require(id));
        t.put("lines", db.rows("SELECT l.id, l.\"productId\", pr.name AS \"productName\", u.code AS \"uomCode\", l.\"plannedQty\", l.\"loadedQty\","
                + " l.\"dispatchedQty\", l.\"deliveredQty\", l.\"returnedQty\", l.\"damagedQty\", l.\"rejectedQty\", l.\"billableQty\" FROM"
                + " \"FleetLoadLine\" l LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\""
                + " WHERE l.\"loadId\" = ? ORDER BY l.\"createdAt\"", id));
        t.put("pod", db.row("SELECT \"receiverName\", \"receiverPhone\", \"receivedAt\", notes FROM \"FleetProofOfDelivery\" WHERE"
                + " \"loadId\" = ? AND \"organizationId\" = ?", id, org()));
        t.put("photos", db.rows("SELECT id, \"fileId\", caption, \"createdAt\" FROM \"SalesDeliveryPhoto\" WHERE \"loadId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"createdAt\"", id, org()));
        return t;
    }

    /** Plan a delivery of a confirmed order, typing the vehicle and driver. Without lines, everything still open. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(String orderId, Map<String, Object> in) {
        Map<String, Object> o = orders.require(orderId);
        if (!SalesOrderService.OPEN.contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed sales orders can be delivered");
        }
        String plate = str(in.get("vehiclePlate"));
        if (blank(plate)) {
            throw ApiException.badRequest("vehiclePlate should not be empty");
        }
        String driver = str(in.get("driverName"));
        BigDecimal gross = in.get("gross") == null ? null : dec(in.get("gross"));
        BigDecimal tare = in.get("tare") == null ? null : dec(in.get("tare"));
        if ((gross == null) != (tare == null)) {
            throw ApiException.badRequest("Give both gross and tare, or neither");
        }
        BigDecimal net = gross == null ? null : gross.subtract(tare);
        if (net != null && net.signum() <= 0) {
            throw ApiException.badRequest("Gross must be greater than tare");
        }
        String deliveryOrder = openDeliveryOrder(orderId, in);
        Map<String, String> bySource = new LinkedHashMap<>();
        Map<String, BigDecimal> remaining = new LinkedHashMap<>();
        for (Map<String, Object> l : deliveryOrders.lines(deliveryOrder)) {
            bySource.put(str(l.get("sourceLineId")), str(l.get("id")));
            remaining.put(str(l.get("id")), dec(l.get("remainingQty")));
        }
        List<LoadLineInput> lines = new ArrayList<>();
        if (in.get("lines") instanceof List<?> raw && !raw.isEmpty()) {
            for (Map<String, Object> l : (List<Map<String, Object>>) raw) {
                String doLine = bySource.get(str(l.get("lineId")));
                if (doLine == null) {
                    throw ApiException.notFound("Order line not found");
                }
                lines.add(new LoadLineInput(doLine, net != null && raw.size() == 1 ? net : dec(l.get("quantity")), str(l.get(
                        "batchNumber"))));
            }
        } else {
            remaining.forEach((line, qty) -> {
                if (qty.signum() > 0) {
                    lines.add(new LoadLineInput(line, net != null && remaining.size() == 1 ? net : qty, null));
                }
            });
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Nothing left to deliver on this order");
        }
        if (net != null && lines.size() > 1) {
            throw ApiException.badRequest("A weighed delivery carries one line; split it into one delivery per product");
        }
        Map<String, Object> load = dispatch.addLoad(deliveryOrder, new LoadInput(in.get("plannedDate") == null ? LocalDate.now()
                : SalesDb.date(str(in.get("plannedDate"))), lines, "Sales delivery", str(in.get("notes"))));
        String id = str(load.get("id"));
        boolean approval = Boolean.TRUE.equals(settings.settings().get("deliveryPostingApproval"));
        db.update("UPDATE \"FleetLoad\" SET carrier = 'manual', \"transporterPlate\" = ?, \"transporterDriverName\" = ?, \"manualGross\" = ?,"
                + " \"manualTare\" = ?, \"approvalRequired\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", plate,
                driver, gross, tare, approval, id, org());
        audit.record(AuditInput.of("FleetLoad", id, "create", null, Obj.of("orderId", orderId, "plate", plate, "net", net)));
        return get(id);
    }

    private String openDeliveryOrder(String orderId, Map<String, Object> in) {
        Map<String, Object> existing = db.row("SELECT id FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'sales_order' AND \"sourceId\" = ? AND status NOT IN ('cancelled', 'closed') ORDER BY \"createdAt\" LIMIT 1", org(),
                orderId);
        if (existing != null) {
            return str(existing.get("id"));
        }
        return str(deliveryOrders.fromSalesOrder(new FromSourceInput(orderId, blank(str(in.get("siteId"))) ? null
                : str(in.get("siteId")), blank(str(in.get("locationId"))) ? null : str(in.get("locationId")), null, null, null,
                null, null, null, null, str(in.get("notes")))).get("id"));
    }

    /** Posting approval (when the setting is on): nobody approves their own delivery. */
    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> t = require(id);
        if (!Boolean.TRUE.equals(t.get("approvalRequired"))) {
            throw ApiException.badRequest("This delivery needs no approval");
        }
        Map<String, Object> raw = db.row("SELECT \"createdBy\" FROM \"FleetLoad\" WHERE id = ?", id);
        if (user() != null && user().equals(raw.get("createdBy"))) {
            throw ApiException.forbidden("You cannot approve your own delivery");
        }
        db.update("UPDATE \"FleetLoad\" SET \"approvedAt\" = " + NOW + ", \"approvedById\" = ? WHERE id = ? AND \"organizationId\" = ?", user(),
                id, org());
        audit.record(AuditInput.of("FleetLoad", id, "approve"));
        return get(id);
    }

    /** Issue the stock and post cost of goods. The order's quantities update at once. */
    @Transactional
    public Map<String, Object> issue(String id) {
        Map<String, Object> t = require(id);
        if (Boolean.TRUE.equals(t.get("approvalRequired")) && t.get("approvedAt") == null) {
            throw ApiException.badRequest("This delivery is waiting for approval");
        }
        dispatch.dispatch(id, null);
        fulfilment.rebuild(str(t.get("orderId")));
        return get(id);
    }

    /**
     * Proof of delivery. Per line: {@code deliveredQty} accepted, {@code rejectedQty} refused and put back into stock,
     * {@code damagedQty} lost in transit. A difference needs a reason and raises the usual discrepancy.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> confirm(String id, Map<String, Object> in) {
        Map<String, Object> t = require(id);
        List<PodLineInput> lines = new ArrayList<>();
        List<Map<String, Object>> raw = in.get("lines") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (raw.isEmpty()) {
            throw ApiException.badRequest("lines should not be empty");
        }
        for (Map<String, Object> l : raw) {
            BigDecimal delivered = dec(l.get("deliveredQty"));
            BigDecimal rejected = dec(l.get("rejectedQty"));
            BigDecimal damaged = dec(l.get("damagedQty"));
            if (delivered.signum() < 0 || rejected.signum() < 0 || damaged.signum() < 0) {
                throw ApiException.badRequest("Quantities must not be less than 0");
            }
            String reason = str(l.get("reason"));
            if (blank(reason) && damaged.signum() > 0) {
                reason = "damaged";
            } else if (blank(reason) && rejected.signum() > 0) {
                reason = "customer_rejected";
            }
            lines.add(new PodLineInput(str(l.get("loadLineId")), delivered, rejected.signum() > 0 ? rejected : null, blank(reason)
                    ? null : reason, str(l.get("notes"))));
        }
        String receiver = str(in.get("receiverName"));
        if (blank(receiver)) {
            throw ApiException.badRequest("receiverName should not be empty");
        }
        dispatch.proofOfDelivery(id, new PodInput(receiver, str(in.get("receiverPhone")), in.get("receivedAt") == null ? null
                : Instant.parse(str(in.get("receivedAt"))), null, null, lines, str(in.get("signature")), null, str(in.get("notes"))));
        for (Map<String, Object> l : raw) {
            db.update("UPDATE \"FleetLoadLine\" SET \"damagedQty\" = ?, \"rejectedQty\" = ? WHERE id = ? AND \"loadId\" = ? AND"
                    + " \"organizationId\" = ?", dec(l.get("damagedQty")), dec(l.get("rejectedQty")), str(l.get("loadLineId")), id, org());
        }
        fulfilment.rebuild(str(t.get("orderId")));
        return get(id);
    }

    /** Add a delivery photo: {@code dataUrl} is {@code data:image/...;base64,...}. */
    @Transactional
    public Map<String, Object> addPhoto(String id, Map<String, Object> in) {
        require(id);
        String url = str(in.get("dataUrl"));
        if (blank(url) || !url.startsWith("data:image/") || !url.contains(";base64,")) {
            throw ApiException.badRequest("Photos must be image files");
        }
        String contentType = url.substring(5, url.indexOf(';'));
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(url.substring(url.indexOf(',') + 1));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Photos must be image files");
        }
        if (bytes.length > 8 * 1024 * 1024) {
            throw ApiException.badRequest("A photo may be at most 8 MB");
        }
        Map<String, Object> file = files.upload("delivery-" + id + "." + contentType.substring(6), contentType, bytes,
                "sales_delivery", id);
        db.insert("SalesDeliveryPhoto", Obj.of("loadId", id, "fileId", file.get("id"), "caption", str(in.get("caption")),
                "createdById", user()));
        return get(id);
    }

    public FleetDeliveryNoteService.Pdf note(String id) {
        require(id);
        return notes.render(id);
    }

    /** Order lines against what moved: ordered, dispatched, delivered, damaged, rejected, billable, invoiced, variance. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> reconciliation(Map<String, String> q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE o.\"organizationId\" = ? AND o.status IN ('confirmed', 'in_fulfilment', 'done') AND"
                + " o.\"deletedAt\" IS NULL");
        where.append(access.branchClause("o.\"branchId\"", params));
        if (!blank(q.get("orderId"))) {
            where.append(" AND o.id = ?");
            params.add(q.get("orderId"));
        }
        if (!blank(q.get("partnerId"))) {
            where.append(" AND o.\"partnerId\" = ?");
            params.add(q.get("partnerId"));
        }
        if ("true".equals(q.get("varianceOnly"))) {
            where.append(" AND (l.\"qtyDelivered\" <> l.\"qtyAccepted\" OR l.\"qtyInvoiced\" - l.\"qtyCredited\" <> l.\"qtyBillable\")");
        }
        return db.rows("""
                SELECT o.id AS "orderId", o.number, p.name AS "partnerName", l.id AS "lineId", l.description, l.quantity - l."qtyCancelled" AS ordered,
                       l."qtyDispatched" AS dispatched, l."qtyAccepted" AS accepted, l."qtyBillable" AS billable,
                       l."qtyInvoiced" - l."qtyCredited" AS invoiced,
                       COALESCE(x.damaged, 0) AS damaged, COALESCE(x.rejected, 0) AS rejected,
                       l."qtyDispatched" - l."qtyAccepted" AS "deliveryVariance",
                       l."qtyBillable" - (l."qtyInvoiced" - l."qtyCredited") AS "toInvoice",
                       CASE WHEN l.quantity - l."qtyCancelled" > 0 THEN round(l."qtyAccepted" * 100 / (l.quantity - l."qtyCancelled"), 2) ELSE 0 END AS "percentDelivered"
                  FROM "SalesOrderLine" l JOIN "SalesOrder" o ON o.id = l."orderId" LEFT JOIN "Partner" p ON p.id = o."partnerId"
                  LEFT JOIN (SELECT dl."sourceLineId" AS line, sum(ll."damagedQty") AS damaged, sum(ll."rejectedQty") AS rejected
                               FROM "FleetLoadLine" ll JOIN "FleetDeliveryOrderLine" dl ON dl.id = ll."deliveryOrderLineId"
                              WHERE ll."organizationId" = current_setting('app.org_id', true) GROUP BY 1) x ON x.line = l.id
                """ + where + " ORDER BY o.\"createdAt\" DESC, l.\"sortOrder\" LIMIT 1000", params);
    }
}
