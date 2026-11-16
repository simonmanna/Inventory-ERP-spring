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
import com.inventoryerp.fleet.application.DispatchCommands.ReturnInput;
import com.inventoryerp.fleet.application.DispatchCommands.TripPatch;
import com.inventoryerp.fleet.application.FleetDeliveryNoteService;
import com.inventoryerp.fleet.application.FleetDeliveryOrderService;
import com.inventoryerp.fleet.application.DispatchCommands;
import com.inventoryerp.fleet.application.FleetDispatchService;
import com.inventoryerp.fleet.application.FleetWeighbridgeRuleService;
import com.inventoryerp.fleet.application.WeighbridgeTicketService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales deliveries (SG-9): always on, whatever the Fleet switch says. A delivery is the same delivery order and load the
 * Fleet module uses, so stock, costing and billing run through one path; here the vehicle and driver are typed in instead
 * of chosen from fleet records. Damaged and rejected quantities and several photos extend the proof of delivery.
 */
@Service
public class SalesDeliveryService {

    /** Per-delivery stage shown on the order: scheduled → dispatched → in transit → (partially) delivered, or failed / returned / cancelled. */
    static final String STAGE = """
            CASE WHEN t.status IN ('planned', 'loading', 'weighed') THEN 'scheduled'
                 WHEN t.status = 'dispatched' THEN 'dispatched'
                 WHEN t.status = 'in_transit' THEN 'in_transit'
                 WHEN t.status IN ('delivered', 'closed') THEN CASE WHEN EXISTS (SELECT 1 FROM "FleetLoadLine" x WHERE x."loadId" = t.id
                      AND COALESCE(x."deliveredQty", 0) < COALESCE(x."dispatchedQty", 0)) THEN 'partially_delivered' ELSE 'delivered' END
                 WHEN t.status = 'returned' THEN CASE WHEN t."attemptFailed" THEN 'failed' ELSE 'returned' END
                 ELSE 'cancelled' END""";

    private static final String LIST = """
            SELECT t.id, t.code, t.status, (""" + STAGE + """
                   ) AS stage, t.carrier, t."transporterPlate" AS "vehiclePlate", t."transporterDriverName" AS "driverName",
                   t."plannedDate", t."scheduledTime", t."dispatchedAt", t."departedAt", t."deliveredAt", t."cancelledAt", t."returnedAt",
                   t."deliveryNoteNumber", t."approvalRequired", t."approvedAt",
                   wt.id AS "weighTicketId", wt."ticketNumber" AS "weighTicketNumber", wt."netKg" AS "weighNetKg",
                   wt.status AS "weighTicketStatus", wt."withinTolerance" AS "weighWithinTolerance",
                   t."manualGross", t."manualTare", t."cancelReason", t."returnReason", t."attemptFailed", t."rescheduledFromId",
                   t."deliveryMethod", t."driverPhone", t."vehicleType", t."transportCompany", t."trackingNumber",
                   t."destinationLocationId", dl.name AS "destinationLocationName", t."deliveryAddress", t."siteContactName",
                   t."siteContactPhone", t.route, t."offloadingEquipment", t."expectedArrivalAt", t."arrivedAt", t."transportCost",
                   t."tripExpenses", t.notes, t."locationId", loc.name AS "locationName", t."createdAt",
                   d.id AS "deliveryOrderId", d.code AS "deliveryOrderCode",
                   d."sourceId" AS "orderId", d."sourceNumber" AS "orderNumber", d."branchId", p.name AS "partnerName",
                   COALESCE((SELECT sum(l."plannedQty") FROM "FleetLoadLine" l WHERE l."loadId" = t.id), 0) AS "plannedQty",
                   COALESCE((SELECT sum(l."dispatchedQty") FROM "FleetLoadLine" l WHERE l."loadId" = t.id), 0) AS "dispatchedQty",
                   COALESCE((SELECT sum(l."deliveredQty") FROM "FleetLoadLine" l WHERE l."loadId" = t.id), 0) AS "deliveredQty",
                   COALESCE((SELECT sum(l."damagedQty") FROM "FleetLoadLine" l WHERE l."loadId" = t.id), 0) AS "damagedQty",
                   COALESCE((SELECT sum(l."rejectedQty") FROM "FleetLoadLine" l WHERE l."loadId" = t.id), 0) AS "rejectedQty",
                   (SELECT count(*) FROM "FleetLoadLine" l WHERE l."loadId" = t.id) AS "lineCount",
                   (SELECT string_agg(DISTINCT u.code, ', ') FROM "FleetLoadLine" l JOIN "UnitOfMeasure" u ON u.id = l."uomId"
                     WHERE l."loadId" = t.id) AS "uomCodes"
              FROM "FleetLoad" t
              JOIN "FleetDeliveryOrder" d ON d.id = t."deliveryOrderId"
              LEFT JOIN "Partner" p ON p.id = d."partnerId"
              LEFT JOIN "InventoryLocation" loc ON loc.id = t."locationId"
              LEFT JOIN "InventoryLocation" dl ON dl.id = t."destinationLocationId"
              LEFT JOIN "WeighbridgeTicket" wt ON t."weighSourceType" = 'weighbridge_ticket' AND wt.id = t."weighSourceId"
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
    private final WeighbridgeTicketService weighTickets;
    private final FleetWeighbridgeRuleService weighRules;

    public SalesDeliveryService(SalesDb db, SalesOrderService orders, SalesFulfilmentService fulfilment,
            SalesSettingsService settings, SalesAccess access, FleetDeliveryOrderService deliveryOrders,
            FleetDispatchService dispatch, FleetDeliveryNoteService notes, FilesService files, AuditService audit,
            WeighbridgeTicketService weighTickets, FleetWeighbridgeRuleService weighRules) {
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
        this.weighTickets = weighTickets;
        this.weighRules = weighRules;
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
        List<Map<String, Object>> rows = db.rows(LIST + where + " ORDER BY t.\"createdAt\" DESC LIMIT 500", params);
        rows.forEach(SalesDeliveryService::weighVerification);
        return rows;
    }

    private Map<String, Object> require(String id) {
        List<Object> params = new ArrayList<>(List.of(id, org()));
        String scope = access.branchClause("d.\"branchId\"", params);
        Map<String, Object> t = db.row(LIST + " WHERE t.id = ? AND t.\"organizationId\" = ? AND d.\"sourceType\" = 'sales_order'"
                + scope, params.toArray());
        if (t == null) {
            throw ApiException.notFound("Delivery not found");
        }
        weighVerification(t);
        return t;
    }

    /** Verification of the ticket that fed the trip (null without one), as the fleet read model derives it. */
    private static void weighVerification(Map<String, Object> row) {
        row.put("weighVerification", row.get("weighTicketId") == null ? null : FleetWeighbridgeRuleService.verification(
                Obj.of("status", row.get("weighTicketStatus"), "withinTolerance", row.get("weighWithinTolerance"))));
    }

    /** Delivery methods of one delivery. */
    static final Set<String> METHODS = Set.of("customer_collect", "site_delivery", "warehouse_transfer", "courier");

    /** Reason codes of a difference at delivery (same list as the fleet discrepancy). */
    static final Set<String> REASONS = Set.of("spillage", "weighing_variance", "damaged", "customer_rejected",
            "wrong_product", "theft", "site_closed", "other");

    /** Free-text logistics of one delivery, with their maximum length. */
    private static final Map<String, Integer> TEXT_FIELDS = Map.ofEntries(Map.entry("driverPhone", 40),
            Map.entry("vehicleType", 80), Map.entry("transportCompany", 160), Map.entry("trackingNumber", 120),
            Map.entry("deliveryAddress", 500), Map.entry("siteContactName", 160), Map.entry("siteContactPhone", 40),
            Map.entry("route", 500), Map.entry("offloadingEquipment", 160));

    /** Fields that may still change once the goods left: tracking, arrival estimate and costs. */
    private static final Set<String> ON_ROAD_FIELDS = Set.of("driverPhone", "trackingNumber", "route", "expectedArrivalAt",
            "transportCost", "tripExpenses", "siteContactName", "siteContactPhone");

    /** Fields that may change after delivery: the trip costs only. */
    private static final Set<String> COST_FIELDS = Set.of("transportCost", "tripExpenses");

    private static final Set<String> UPDATABLE;

    static {
        Set<String> u = new java.util.HashSet<>(TEXT_FIELDS.keySet());
        u.addAll(List.of("deliveryMethod", "scheduledTime", "destinationLocationId", "expectedArrivalAt", "transportCost",
                "tripExpenses", "plannedDate", "locationId", "vehiclePlate", "driverName", "notes", "lines"));
        UPDATABLE = Set.copyOf(u);
    }

    /** One delivery: lines with damaged and rejected quantities, proof of delivery, photos and discrepancies. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> t = new LinkedHashMap<>(require(id));
        t.put("lines", db.rows("SELECT l.id, l.\"deliveryOrderLineId\", dl.\"sourceLineId\" AS \"orderLineId\", l.\"productId\","
                + " pr.name AS \"productName\", u.code AS \"uomCode\", l.\"plannedQty\", l.\"loadedQty\", l.\"dispatchedQty\","
                + " l.\"deliveredQty\", l.\"returnedQty\", l.\"damagedQty\", l.\"rejectedQty\", l.\"billableQty\", l.\"batchNumber\","
                + " CASE WHEN l.\"deliveredQty\" IS NULL THEN 0 ELSE GREATEST(COALESCE(l.\"dispatchedQty\", 0) - l.\"deliveredQty\""
                + " - COALESCE(l.\"returnedQty\", 0) - l.\"damagedQty\", 0) END AS \"shortageQty\" FROM \"FleetLoadLine\" l"
                + " LEFT JOIN \"FleetDeliveryOrderLine\" dl ON dl.id = l.\"deliveryOrderLineId\" LEFT JOIN \"Product\" pr ON"
                + " pr.id = l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\"loadId\" = ? ORDER BY"
                + " dl.\"lineNumber\", l.\"createdAt\"", id));
        Map<String, Object> pod = db.row("SELECT \"receiverName\", \"receiverPhone\", \"receivedAt\", notes, \"signatureFileId\""
                + " FROM \"FleetProofOfDelivery\" WHERE \"loadId\" = ? AND \"organizationId\" = ?", id, org());
        if (pod != null) {
            pod.put("signatureUrl", url(str(pod.remove("signatureFileId"))));
        }
        t.put("pod", pod);
        t.put("customerWeight", customerWeightView(id));
        List<Map<String, Object>> photos = db.rows("SELECT id, \"fileId\", caption, \"createdAt\" FROM \"SalesDeliveryPhoto\""
                + " WHERE \"loadId\" = ? AND \"organizationId\" = ? ORDER BY \"createdAt\"", id, org());
        photos.forEach(p -> p.put("url", url(str(p.get("fileId")))));
        t.put("photos", photos);
        List<Map<String, Object>> tickets = weighTickets.ticketsForTrips(List.of(id));
        t.put("weighbridgeTickets", tickets);
        t.put("weighbridge", Obj.of("required", weighRules.forTrip(id).required(), "feedingTicketId",
                tickets.stream().filter(w -> Boolean.TRUE.equals(w.get("feedsTrip"))).map(w -> w.get("id")).findFirst()
                        .orElse(null)));
        return t;
    }

    /**
     * Phase A A2: the customer's weighing (evidence, frozen comparison, evidence file links) and the weight claims it
     * raised; null before any evidence.
     */
    private Map<String, Object> customerWeightView(String id) {
        Map<String, Object> w = db.row("SELECT \"customerGrossKg\", \"customerTareKg\", \"customerNetKg\","
                + " \"customerTicketRef\", \"customerWeighedAt\", \"customerWeightNotes\", \"customerWeightAt\","
                + " \"customerWeightFileIds\", \"quarryNetKg\", \"quarryWeightSource\", \"weightVarianceKg\","
                + " \"allowedVarianceKg\", \"weightWithinTolerance\", \"weightOutcome\", \"unallocatedVarianceKg\""
                + " FROM \"FleetProofOfDelivery\" WHERE \"loadId\" = ? AND \"organizationId\" = ?", id, org());
        if (w == null) {
            return null;
        }
        List<String> urls = new ArrayList<>();
        Object ids = w.remove("customerWeightFileIds");
        if (ids instanceof java.sql.Array a) {
            try {
                for (Object f : (Object[]) a.getArray()) {
                    urls.add(url(str(f)));
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        } else if (ids instanceof Object[] arr) {
            for (Object f : arr) {
                urls.add(url(str(f)));
            }
        } else if (ids instanceof List<?> list) {
            list.forEach(f -> urls.add(url(str(f))));
        }
        w.put("evidenceUrls", urls);
        w.put("claims", db.rows("SELECT x.id, x.code, x.\"loadLineId\", x.\"differenceQty\" AS quantity, x.\"claimKg\","
                + " x.status, x.\"decidedAt\", x.\"decisionNotes\", x.\"withdrawnAt\" FROM \"FleetDeliveryDiscrepancy\" x WHERE"
                + " x.\"loadId\" = ? AND x.\"organizationId\" = ? AND x.kind = 'weight' ORDER BY x.\"createdAt\"", id, org()));
        return w.get("customerNetKg") == null && urls.isEmpty() ? null : w;
    }

    /**
     * Phase A A2: record or correct the customer's weighing of a delivered delivery (plan §7). Same body as the
     * {@code customerWeight} object of {@link #confirm}. Returns the delivery with {@code customerWeightResult}.
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> customerWeight(String id, Map<String, Object> in) {
        require(id);
        Map<String, Object> result = (Map<String, Object>) dispatch.recordCustomerWeight(id, customerWeightInput(in))
                .get("customerWeight");
        Map<String, Object> out = get(id);
        out.put("customerWeightResult", result);
        return out;
    }

    /** Phase A A2: a photo or PDF of the customer's weighbridge ticket ({@code dataUrl}). */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> customerWeightEvidence(String id, Map<String, Object> in) {
        require(id);
        String url = str(in.get("dataUrl"));
        if (blank(url) || !(url.startsWith("data:image/") || url.startsWith("data:application/pdf"))
                || !url.contains(";base64,")) {
            throw ApiException.badRequest("Customer weight evidence must be an image or a PDF");
        }
        String contentType = url.substring(5, url.indexOf(';'));
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(url.substring(url.indexOf(',') + 1));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Customer weight evidence must be an image or a PDF");
        }
        if (bytes.length > 8 * 1024 * 1024) {
            throw ApiException.badRequest("A photo may be at most 8 MB");
        }
        String ext = contentType.substring(contentType.indexOf('/') + 1);
        dispatch.customerWeightPhoto(id, "customer-weight-" + id + "." + ext, contentType, bytes);
        return get(id);
    }

    private static DispatchCommands.CustomerWeightInput customerWeightInput(Map<String, Object> in) {
        if (in == null) {
            throw ApiException.badRequest("customerWeight must be an object");
        }
        List<DispatchCommands.WeightShareInput> shares = null;
        if (in.get("allocations") instanceof List<?> raw) {
            shares = new ArrayList<>();
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw ApiException.badRequest("allocations must be a list of objects");
                }
                String line = str(m.get("loadLineId"));
                if (blank(line)) {
                    throw ApiException.badRequest("allocations.loadLineId should not be empty");
                }
                BigDecimal qty = number("allocations.quantity", m.get("quantity"));
                BigDecimal kg = m.get("kg") == null ? null : number("allocations.kg", m.get("kg"));
                if (qty.signum() < 0 || (kg != null && kg.signum() < 0)) {
                    throw ApiException.badRequest("Quantities must not be less than 0");
                }
                shares.add(new DispatchCommands.WeightShareInput(line, qty, kg));
            }
        }
        BigDecimal gross = in.get("grossKg") == null ? null : number("grossKg", in.get("grossKg"));
        BigDecimal tare = in.get("tareKg") == null ? null : number("tareKg", in.get("tareKg"));
        BigDecimal net = in.get("netKg") == null ? null : number("netKg", in.get("netKg"));
        for (BigDecimal v : java.util.Arrays.asList(gross, tare, net)) {
            if (v != null && v.signum() < 0) {
                throw ApiException.badRequest("Weights must not be less than 0");
            }
        }
        String ref = str(in.get("ticketRef"));
        if (ref != null && ref.length() > 80) {
            throw ApiException.badRequest("ticketRef must be shorter than or equal to 80 characters");
        }
        return new DispatchCommands.CustomerWeightInput(gross, tare, net, ref, timestamp("weighedAt",
                in.get("weighedAt")), str(in.get("notes")), shares);
    }

    private String url(String fileId) {
        if (fileId == null) {
            return null;
        }
        try {
            Object u = files.signDownload(fileId).get("url");
            return u == null ? null : u.toString();
        } catch (ApiException e) {
            return null;
        }
    }

    /**
     * Plan a delivery of a confirmed order: method, schedule, dispatch warehouse, transport and quantities per order
     * line. Without lines, everything still open. A line quantity of 0 is skipped; more than remains is refused.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(String orderId, Map<String, Object> in) {
        Map<String, Object> o = orders.require(orderId);
        // One planner per order at a time: two first deliveries must not open two delivery orders and plan twice.
        db.row("SELECT pg_advisory_xact_lock(hashtext(?))::text AS locked", "sales-delivery:" + orderId);
        if (!SalesOrderService.OPEN.contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed sales orders can be delivered");
        }
        Map<String, Object> logistics = logistics(in, true);
        String method = Objects.requireNonNullElse(str(logistics.get("deliveryMethod")), "site_delivery");
        logistics.put("deliveryMethod", method);
        String plate = blank(str(in.get("vehiclePlate"))) ? null : str(in.get("vehiclePlate")).trim();
        if (plate == null && !plateOptional(method)) {
            throw ApiException.badRequest("vehiclePlate should not be empty");
        }
        assertMethodDetails(method, logistics);
        String driver = blank(str(in.get("driverName"))) ? null : str(in.get("driverName")).trim();
        BigDecimal gross = in.get("gross") == null ? null : number("gross", in.get("gross"));
        BigDecimal tare = in.get("tare") == null ? null : number("tare", in.get("tare"));
        if ((gross == null) != (tare == null)) {
            throw ApiException.badRequest("Give both gross and tare, or neither");
        }
        BigDecimal net = gross == null ? null : gross.subtract(tare);
        if (net != null && net.signum() <= 0) {
            throw ApiException.badRequest("Gross must be greater than tare");
        }
        String rescheduledFrom = blank(str(in.get("rescheduledFromId"))) ? null : str(in.get("rescheduledFromId"));
        if (rescheduledFrom != null) {
            Map<String, Object> prev = require(rescheduledFrom);
            if (!orderId.equals(prev.get("orderId"))) {
                throw ApiException.badRequest("The delivery being rescheduled belongs to another order");
            }
            if (!List.of("returned", "cancelled").contains(str(prev.get("status")))) {
                throw ApiException.badRequest("Only a failed, returned or cancelled delivery can be rescheduled");
            }
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
            List<Map<String, Object>> given = (List<Map<String, Object>>) raw;
            for (Map<String, Object> l : given) {
                String doLine = bySource.get(str(l.get("lineId")));
                if (doLine == null) {
                    throw ApiException.notFound("Order line not found");
                }
                BigDecimal qty = number("quantity", l.get("quantity"));
                if (qty.signum() < 0) {
                    throw ApiException.badRequest("Quantities must not be less than 0");
                }
                if (qty.signum() == 0 && !(net != null && given.size() == 1)) {
                    continue;
                }
                lines.add(new LoadLineInput(doLine, net != null && given.size() == 1 ? net : qty, str(l.get("batchNumber"))));
            }
            if (lines.isEmpty()) {
                throw ApiException.badRequest("Enter a quantity for at least one product");
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
        LocalDate planned = in.get("plannedDate") == null ? LocalDate.now() : SalesDb.date(str(in.get("plannedDate")));
        Map<String, Object> load = dispatch.addLoad(deliveryOrder, new LoadInput(planned, lines, "Sales delivery",
                blank(str(in.get("notes"))) ? null : str(in.get("notes"))));
        String id = str(load.get("id"));
        if (!blank(str(in.get("locationId")))) {
            dispatch.update(id, new TripPatch(null, str(in.get("locationId")), null, null, null));
        }
        boolean approval = Boolean.TRUE.equals(settings.settings().get("deliveryPostingApproval"));
        db.update("UPDATE \"FleetLoad\" SET carrier = 'manual', \"transporterPlate\" = ?, \"transporterDriverName\" = ?, \"manualGross\" = ?,"
                + " \"manualTare\" = ?, \"approvalRequired\" = ?, \"rescheduledFromId\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                + " AND \"organizationId\" = ?", plate, driver, gross, tare, approval, rescheduledFrom, id, org());
        writeLogistics(id, logistics);
        audit.record(AuditInput.of("FleetLoad", id, "create", null, Obj.of("orderId", orderId, "plate", plate, "net", net,
                "method", method, "plannedDate", planned.toString(), "rescheduledFromId", rescheduledFrom)));
        return get(id);
    }

    private static boolean plateOptional(String method) {
        return "customer_collect".equals(method) || "courier".equals(method);
    }

    private static void assertMethodDetails(String method, Map<String, Object> logistics) {
        if ("warehouse_transfer".equals(method) && logistics.get("destinationLocationId") == null) {
            throw ApiException.badRequest("destinationLocationId should not be empty for a warehouse transfer");
        }
        if ("courier".equals(method) && logistics.get("transportCompany") == null) {
            throw ApiException.badRequest("transportCompany should not be empty for a courier delivery");
        }
    }

    /**
     * Validated logistics values of {@code in} (only the keys present, blank = null). {@code all} = creation, so every
     * known key is read.
     */
    private Map<String, Object> logistics(Map<String, Object> in, boolean all) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> f : TEXT_FIELDS.entrySet()) {
            if (all || in.containsKey(f.getKey())) {
                String v = str(in.get(f.getKey()));
                v = blank(v) ? null : v.trim();
                if (v != null && v.length() > f.getValue()) {
                    throw ApiException.badRequest(f.getKey() + " must be shorter than or equal to " + f.getValue() + " characters");
                }
                out.put(f.getKey(), v);
            }
        }
        if (all || in.containsKey("deliveryMethod")) {
            String m = blank(str(in.get("deliveryMethod"))) ? null : str(in.get("deliveryMethod"));
            if (m != null && !METHODS.contains(m)) {
                throw ApiException.badRequest("deliveryMethod must be one of: customer_collect, site_delivery, warehouse_transfer, courier");
            }
            out.put("deliveryMethod", m);
        }
        if (all || in.containsKey("scheduledTime")) {
            String time = blank(str(in.get("scheduledTime"))) ? null : str(in.get("scheduledTime")).trim();
            if (time != null && !time.matches("^([01][0-9]|2[0-3]):[0-5][0-9]$")) {
                throw ApiException.badRequest("scheduledTime must be a time HH:mm");
            }
            out.put("scheduledTime", time);
        }
        if (all || in.containsKey("destinationLocationId")) {
            String loc = blank(str(in.get("destinationLocationId"))) ? null : str(in.get("destinationLocationId"));
            if (loc != null && db.count("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?",
                    List.of(loc, org())) == 0) {
                throw ApiException.badRequest("Destination warehouse not found");
            }
            out.put("destinationLocationId", loc);
        }
        if (all || in.containsKey("expectedArrivalAt")) {
            out.put("expectedArrivalAt", timestamp("expectedArrivalAt", in.get("expectedArrivalAt")));
        }
        for (String k : COST_FIELDS) {
            if (all || in.containsKey(k)) {
                BigDecimal v = in.get(k) == null || blank(str(in.get(k))) ? null : number(k, in.get(k));
                if (v != null && v.signum() < 0) {
                    throw ApiException.badRequest(k + " must not be less than 0");
                }
                out.put(k, v);
            }
        }
        return out;
    }

    private void writeLogistics(String id, Map<String, Object> values) {
        if (values.isEmpty()) {
            return;
        }
        StringBuilder set = new StringBuilder();
        List<Object> params = new ArrayList<>();
        values.forEach((k, v) -> {
            set.append(set.isEmpty() ? "" : ", ").append('"').append(k).append("\" = ?");
            params.add(v);
        });
        params.add(id);
        params.add(org());
        db.update("UPDATE \"FleetLoad\" SET " + set + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?",
                params.toArray());
    }

    private static BigDecimal number(String field, Object v) {
        if (v == null || blank(str(v))) {
            throw ApiException.badRequest(field + " must be a number");
        }
        try {
            return dec(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be a number");
        }
    }

    private static Instant timestamp(String field, Object v) {
        if (v == null || blank(str(v))) {
            return null;
        }
        try {
            return Instant.parse(str(v));
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a valid ISO 8601 date string");
        }
    }

    /**
     * Edit a delivery. Before it leaves: schedule (reschedule), warehouse, transport, logistics and — while still
     * planned — the quantities ({@code lines}: order line + quantity, the full new set). On the road: tracking, arrival
     * estimate, contact and costs. Delivered: the costs. Cancelled / returned: nothing.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> update(String id, Map<String, Object> in) {
        for (String k : in.keySet()) {
            if (!UPDATABLE.contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
        Map<String, Object> t = require(id);
        String status = str(t.get("status"));
        boolean open = List.of("planned", "loading", "weighed").contains(status);
        Set<String> allowed = open ? UPDATABLE : List.of("dispatched", "in_transit").contains(status) ? ON_ROAD_FIELDS
                : List.of("delivered", "closed").contains(status) ? COST_FIELDS : Set.of();
        for (String k : in.keySet()) {
            if (!allowed.contains(k) && !Objects.equals(normal(in.get(k)), normal(t.get(k)))) {
                throw ApiException.badRequest("Delivery " + t.get("code") + " is " + str(t.get("stage")).replace('_', ' ')
                        + "; " + k + " can no longer change");
            }
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> logistics = logistics(in, false);
        logistics.keySet().removeIf(k -> !allowed.contains(k));
        String method = logistics.containsKey("deliveryMethod") && logistics.get("deliveryMethod") != null
                ? str(logistics.get("deliveryMethod")) : str(t.get("deliveryMethod"));
        if (open) {
            Map<String, Object> merged = new HashMap<>(t);
            merged.putAll(logistics);
            assertMethodDetails(method == null ? "site_delivery" : method, merged);
        }
        logistics.forEach((k, v) -> {
            if (!Objects.equals(normal(v), normal(t.get(k)))) {
                before.put(k, t.get(k));
                changes.put(k, v instanceof Instant || v instanceof BigDecimal ? v.toString() : v);
            }
        });
        writeLogistics(id, logistics);
        if (open) {
            if (in.containsKey("vehiclePlate") || in.containsKey("driverName")) {
                String plate = in.containsKey("vehiclePlate") ? (blank(str(in.get("vehiclePlate"))) ? null
                        : str(in.get("vehiclePlate")).trim()) : str(t.get("vehiclePlate"));
                String driver = in.containsKey("driverName") ? (blank(str(in.get("driverName"))) ? null
                        : str(in.get("driverName")).trim()) : str(t.get("driverName"));
                if (plate == null && !plateOptional(method)) {
                    throw ApiException.badRequest("vehiclePlate should not be empty");
                }
                db.update("UPDATE \"FleetLoad\" SET \"transporterPlate\" = ?, \"transporterDriverName\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?", plate, driver, id, org());
                if (!Objects.equals(plate, t.get("vehiclePlate"))) {
                    before.put("vehiclePlate", t.get("vehiclePlate"));
                    changes.put("vehiclePlate", plate);
                }
                if (!Objects.equals(driver, t.get("driverName"))) {
                    before.put("driverName", t.get("driverName"));
                    changes.put("driverName", driver);
                }
            }
            LocalDate date = in.get("plannedDate") == null ? null : SalesDb.date(str(in.get("plannedDate")));
            String location = blank(str(in.get("locationId"))) ? null : str(in.get("locationId"));
            boolean newDate = date != null && !date.toString().equals(str(t.get("plannedDate")));
            boolean newLocation = location != null && !location.equals(t.get("locationId"));
            boolean newNotes = in.containsKey("notes") && !Objects.equals(normal(in.get("notes")), normal(t.get("notes")));
            if (newDate || newLocation || newNotes) {
                dispatch.update(id, new TripPatch(newDate ? date : null, newLocation ? location : null, null, null,
                        newNotes ? Objects.requireNonNullElse(str(in.get("notes")), "") : null));
                if (newDate) {
                    before.put("plannedDate", str(t.get("plannedDate")));
                    changes.put("plannedDate", date.toString());
                }
                if (newLocation) {
                    before.put("locationId", t.get("locationId"));
                    changes.put("locationId", location);
                }
            }
            if (in.get("lines") instanceof List<?> raw) {
                Map<String, String> bySource = new HashMap<>();
                for (Map<String, Object> l : deliveryOrders.lines(str(t.get("deliveryOrderId")))) {
                    bySource.put(str(l.get("sourceLineId")), str(l.get("id")));
                }
                List<LoadLineInput> lines = new ArrayList<>();
                for (Map<String, Object> l : (List<Map<String, Object>>) raw) {
                    String doLine = bySource.get(str(l.get("lineId")));
                    if (doLine == null) {
                        throw ApiException.notFound("Order line not found");
                    }
                    BigDecimal qty = number("quantity", l.get("quantity"));
                    if (qty.signum() < 0) {
                        throw ApiException.badRequest("Quantities must not be less than 0");
                    }
                    lines.add(new LoadLineInput(doLine, qty, str(l.get("batchNumber"))));
                }
                dispatch.replan(id, lines);
                fulfilment.rebuild(str(t.get("orderId")));
                changes.put("lines", "re-planned");
            }
        }
        if (!changes.isEmpty()) {
            audit.record(AuditInput.of("FleetLoad", id, "update", before, changes));
        }
        return get(id);
    }

    private static Object normal(Object v) {
        if (v == null || blank(str(v))) {
            return null;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return str(v).trim();
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
                null, null, null, null, blank(str(in.get("notes"))) ? null : str(in.get("notes")))).get("id"));
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

    /** Issue the stock and post cost of goods (dispatch). The order's quantities update at once. */
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

    /** Dispatched → in transit: the goods left the gate. */
    @Transactional
    public Map<String, Object> depart(String id) {
        require(id);
        dispatch.depart(id);
        return get(id);
    }

    /** Cancel a delivery before the goods leave; its quantity can be planned again. */
    @Transactional
    public Map<String, Object> cancel(String id, Map<String, Object> in) {
        Map<String, Object> t = require(id);
        String reason = str(in.get("reason"));
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        dispatch.cancel(id, reason);
        fulfilment.rebuild(str(t.get("orderId")));
        return get(id);
    }

    /**
     * The delivery attempt failed (site closed, nobody to receive, access refused …): the whole load comes back into
     * the dispatch warehouse at its issue cost and its quantity can be scheduled again on a new delivery.
     */
    @Transactional
    public Map<String, Object> fail(String id, Map<String, Object> in) {
        return bringBack(id, in, true);
    }

    /** The customer refused the whole load: it comes back into stock and can be scheduled again. */
    @Transactional
    public Map<String, Object> returnLoad(String id, Map<String, Object> in) {
        return bringBack(id, in, false);
    }

    private Map<String, Object> bringBack(String id, Map<String, Object> in, boolean failed) {
        Map<String, Object> t = require(id);
        String reason = str(in.get("reason"));
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        if (reason.length() > 500) {
            throw ApiException.badRequest("reason must be shorter than or equal to 500 characters");
        }
        String code = blank(str(in.get("reasonCode"))) ? (failed ? "site_closed" : "customer_rejected") : str(in.get("reasonCode"));
        if (!REASONS.contains(code)) {
            throw ApiException.badRequest("reasonCode must be one of: " + String.join(", ", REASONS.stream().sorted().toList()));
        }
        dispatch.returnLoad(id, new ReturnInput(reason.trim(), code));
        if (failed) {
            db.update("UPDATE \"FleetLoad\" SET \"attemptFailed\" = true, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                    + " \"organizationId\" = ?", id, org());
            audit.record(AuditInput.of("FleetLoad", id, "update", Obj.of("status", t.get("status")), Obj.of("status", "failed",
                    "reason", reason.trim())));
        }
        fulfilment.rebuild(str(t.get("orderId")));
        return get(id);
    }

    /**
     * Proof of delivery. Per line: {@code deliveredQty} accepted, {@code rejectedQty} refused and put back into stock,
     * {@code damagedQty} lost in transit; what is left of the dispatched quantity is a shortage. A difference needs a
     * reason and raises the usual discrepancy. No stock moves here except the rejected goods coming back.
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
        Map<String, Map<String, Object>> loadLines = new HashMap<>();
        for (Map<String, Object> l : db.rows("SELECT l.id, l.\"dispatchedQty\", pr.name AS \"productName\" FROM \"FleetLoadLine\" l"
                + " LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" WHERE l.\"loadId\" = ? AND l.\"organizationId\" = ?", id, org())) {
            loadLines.put(str(l.get("id")), l);
        }
        for (Map<String, Object> l : raw) {
            BigDecimal delivered = number("deliveredQty", l.get("deliveredQty"));
            BigDecimal rejected = l.get("rejectedQty") == null ? BigDecimal.ZERO : number("rejectedQty", l.get("rejectedQty"));
            BigDecimal damaged = l.get("damagedQty") == null ? BigDecimal.ZERO : number("damagedQty", l.get("damagedQty"));
            if (delivered.signum() < 0 || rejected.signum() < 0 || damaged.signum() < 0) {
                throw ApiException.badRequest("Quantities must not be less than 0");
            }
            Map<String, Object> ll = loadLines.get(str(l.get("loadLineId")));
            if (ll != null && delivered.add(rejected).add(damaged).compareTo(dec(ll.get("dispatchedQty"))) > 0) {
                throw ApiException.badRequest("Delivered, rejected and damaged quantities of " + ll.get("productName")
                        + " exceed the " + JsNumbers.dec(dec(ll.get("dispatchedQty"))) + " dispatched");
            }
            String reason = str(l.get("reason"));
            if (blank(reason) && damaged.signum() > 0) {
                reason = "damaged";
            } else if (blank(reason) && rejected.signum() > 0) {
                reason = "customer_rejected";
            }
            if (!blank(reason) && !REASONS.contains(reason)) {
                throw ApiException.badRequest("reason must be one of: " + String.join(", ", REASONS.stream().sorted().toList()));
            }
            lines.add(new PodLineInput(str(l.get("loadLineId")), delivered, rejected.signum() > 0 ? rejected : null, blank(reason)
                    ? null : reason, str(l.get("notes"))));
        }
        String receiver = str(in.get("receiverName"));
        if (blank(receiver)) {
            throw ApiException.badRequest("receiverName should not be empty");
        }
        Instant receivedAt = timestamp("receivedAt", in.get("receivedAt"));
        Instant arrivedAt = timestamp("arrivedAt", in.get("arrivedAt"));
        if (arrivedAt != null && receivedAt != null && arrivedAt.isAfter(receivedAt)) {
            throw ApiException.badRequest("The arrival time is after the delivery confirmation time");
        }
        dispatch.proofOfDelivery(id, new PodInput(receiver, str(in.get("receiverPhone")), receivedAt, null, null, lines,
                str(in.get("signature")), null, str(in.get("notes"))));
        for (Map<String, Object> l : raw) {
            db.update("UPDATE \"FleetLoadLine\" SET \"damagedQty\" = ?, \"rejectedQty\" = ? WHERE id = ? AND \"loadId\" = ? AND"
                    + " \"organizationId\" = ?", l.get("damagedQty") == null ? BigDecimal.ZERO : dec(l.get("damagedQty")),
                    l.get("rejectedQty") == null ? BigDecimal.ZERO : dec(l.get("rejectedQty")), str(l.get("loadLineId")), id, org());
        }
        db.update("UPDATE \"FleetLoad\" SET \"arrivedAt\" = COALESCE(?::timestamp(3), \"deliveredAt\") WHERE id = ? AND \"organizationId\" = ?",
                arrivedAt, id, org());
        if (in.get("customerWeight") != null) {
            // Phase A A2: after damaged / rejected are on the lines, so the ceiling excludes them.
            dispatch.recordCustomerWeight(id, customerWeightInput(in.get("customerWeight") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null));
        }
        fulfilment.rebuild(str(t.get("orderId")));
        return get(id);
    }

    /** Status and change history of one delivery (audit trail: who, when, what). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String id) {
        require(id);
        return db.rows("SELECT a.id, a.action::text AS action, a.\"oldValues\", a.\"newValues\", a.\"createdAt\","
                + " COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\", u.\"lastName\")), ''), u.email) AS \"actorName\""
                + " FROM \"AuditLog\" a LEFT JOIN \"User\" u ON u.id = a.\"actorId\" WHERE a.\"organizationId\" = ? AND a.entity ="
                + " 'FleetLoad' AND a.\"entityId\" = ? ORDER BY a.\"createdAt\", a.id", org(), id);
    }

    /**
     * Delivery overview of an order, per line and in total: ordered, scheduled (planned, not yet left), dispatched (on
     * the road), delivered (accepted at proof of delivery), rejected, damaged, shortage, outstanding (ordered −
     * delivered) and what can still be scheduled. A quantity counts in one bucket only. The order's delivery stage is
     * derived from its deliveries, independent of each delivery's own stage.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String orderId) {
        orders.require(orderId);
        List<Map<String, Object>> lines = db.rows("""
                SELECT l.id AS "lineId", l.description, l."productId", pr.name AS "productName", u.code AS "uomCode",
                       l.quantity - l."qtyCancelled" AS ordered, l."unitPrice",
                       COALESCE(x.scheduled, 0) AS scheduled, COALESCE(x.dispatched, 0) AS dispatched,
                       COALESCE(x.delivered, 0) AS delivered, COALESCE(x.rejected, 0) AS rejected,
                       COALESCE(x.damaged, 0) AS damaged, COALESCE(x.shortage, 0) AS shortage, COALESCE(x.returned, 0) AS returned,
                       COALESCE(x.picked, 0) AS picked, x."scheduledDate", x."lastDeliveredAt",
                       l."qtyInvoiced" - l."qtyCredited" AS invoiced, l."qtyReturned" AS "customerReturned"
                  FROM "SalesOrderLine" l
                  LEFT JOIN "Product" pr ON pr.id = l."productId"
                  LEFT JOIN "UnitOfMeasure" u ON u.id = l."uomId"
                  LEFT JOIN (SELECT dl."sourceLineId" AS line,
                                    sum(ll."plannedQty") FILTER (WHERE t.status IN ('planned', 'loading', 'weighed')) AS scheduled,
                                    sum(COALESCE(ll."dispatchedQty", 0)) FILTER (WHERE t.status IN ('dispatched', 'in_transit')) AS dispatched,
                                    sum(COALESCE(ll."deliveredQty", 0)) FILTER (WHERE t.status IN ('delivered', 'closed')) AS delivered,
                                    sum(ll."rejectedQty") FILTER (WHERE t.status IN ('delivered', 'closed')) AS rejected,
                                    sum(ll."damagedQty") FILTER (WHERE t.status IN ('delivered', 'closed')) AS damaged,
                                    sum(GREATEST(COALESCE(ll."dispatchedQty", 0) - COALESCE(ll."deliveredQty", 0)
                                        - COALESCE(ll."returnedQty", 0) - ll."damagedQty", 0)) FILTER (WHERE t.status IN ('delivered', 'closed')) AS shortage,
                                    sum(COALESCE(ll."returnedQty", 0)) FILTER (WHERE t.status = 'returned') AS returned,
                                    sum(COALESCE(ll."loadedQty", ll."dispatchedQty", 0)) FILTER (WHERE t.status IN
                                        ('loading', 'weighed', 'dispatched', 'in_transit', 'delivered', 'closed')) AS picked,
                                    min(t."plannedDate") FILTER (WHERE t.status IN ('planned', 'loading', 'weighed')) AS "scheduledDate",
                                    max(t."deliveredAt") FILTER (WHERE t.status IN ('delivered', 'closed')) AS "lastDeliveredAt"
                               FROM "FleetLoadLine" ll
                               JOIN "FleetLoad" t ON t.id = ll."loadId"
                               JOIN "FleetDeliveryOrderLine" dl ON dl.id = ll."deliveryOrderLineId"
                               JOIN "FleetDeliveryOrder" d ON d.id = dl."deliveryOrderId"
                              WHERE d."organizationId" = ? AND d."sourceType" = 'sales_order' AND d."sourceId" = ?
                                AND d.status <> 'cancelled' AND dl."sourceLineId" IS NOT NULL
                              GROUP BY 1) x ON x.line = l.id
                 WHERE l."orderId" = ? AND l."organizationId" = ?
                 ORDER BY l."sortOrder"
                """, org(), orderId, orderId, org());
        BigDecimal valueOrdered = BigDecimal.ZERO;
        BigDecimal valueDelivered = BigDecimal.ZERO;
        BigDecimal ratioSum = BigDecimal.ZERO;
        int counted = 0;
        boolean allDelivered = true;
        boolean anyDelivered = false;
        boolean anyOnRoad = false;
        boolean anyScheduled = false;
        Map<String, Map<String, BigDecimal>> byUnit = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            BigDecimal ordered = dec(l.get("ordered"));
            BigDecimal scheduled = dec(l.get("scheduled"));
            BigDecimal onRoad = dec(l.get("dispatched"));
            BigDecimal delivered = dec(l.get("delivered"));
            BigDecimal used = scheduled.add(onRoad).add(delivered).add(dec(l.get("damaged"))).add(dec(l.get("shortage")));
            BigDecimal outstanding = ordered.subtract(delivered).max(BigDecimal.ZERO);
            BigDecimal toSchedule = ordered.subtract(used).max(BigDecimal.ZERO);
            l.put("outstanding", JsNumbers.js(outstanding));
            l.put("toSchedule", JsNumbers.js(toSchedule));
            BigDecimal pct = ordered.signum() > 0 ? delivered.min(ordered).multiply(BigDecimal.valueOf(100))
                    .divide(ordered, 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
            l.put("percentDelivered", JsNumbers.js(pct));
            if (ordered.signum() > 0) {
                counted++;
                ratioSum = ratioSum.add(pct);
                BigDecimal price = dec(l.get("unitPrice"));
                valueOrdered = valueOrdered.add(ordered.multiply(price));
                valueDelivered = valueDelivered.add(delivered.min(ordered).multiply(price));
                allDelivered &= delivered.compareTo(ordered) >= 0;
            }
            anyDelivered |= delivered.signum() > 0;
            anyOnRoad |= onRoad.signum() > 0;
            anyScheduled |= scheduled.signum() > 0;
            Map<String, BigDecimal> u = byUnit.computeIfAbsent(Objects.requireNonNullElse(str(l.get("uomCode")), ""),
                    k -> new LinkedHashMap<>());
            u.merge("ordered", ordered, BigDecimal::add);
            u.merge("scheduled", scheduled, BigDecimal::add);
            u.merge("dispatched", onRoad, BigDecimal::add);
            u.merge("delivered", delivered, BigDecimal::add);
            u.merge("outstanding", outstanding, BigDecimal::add);
            u.merge("toSchedule", toSchedule, BigDecimal::add);
            u.merge("rejected", dec(l.get("rejected")), BigDecimal::add);
            u.merge("damaged", dec(l.get("damaged")), BigDecimal::add);
            u.merge("shortage", dec(l.get("shortage")), BigDecimal::add);
        }
        String stage = counted > 0 && allDelivered ? "delivered" : anyDelivered ? "partially_delivered" : anyOnRoad
                ? "dispatched" : anyScheduled ? "scheduled" : "pending";
        BigDecimal percent = valueOrdered.signum() > 0 ? valueDelivered.multiply(BigDecimal.valueOf(100)).divide(valueOrdered, 2,
                java.math.RoundingMode.HALF_UP) : counted > 0 ? ratioSum.divide(BigDecimal.valueOf(counted), 2,
                java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
        Map<String, Object> stages = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows("SELECT (" + STAGE + ") AS stage, count(*) AS n FROM \"FleetLoad\" t JOIN"
                + " \"FleetDeliveryOrder\" d ON d.id = t.\"deliveryOrderId\" WHERE d.\"organizationId\" = ? AND d.\"sourceType\" ="
                + " 'sales_order' AND d.\"sourceId\" = ? GROUP BY 1", org(), orderId)) {
            stages.put(str(r.get("stage")), r.get("n"));
        }
        long trips = stages.entrySet().stream().filter(e -> !e.getKey().equals("cancelled")).mapToLong(e -> dec(e.getValue())
                .longValue()).sum();
        List<Map<String, Object>> units = new ArrayList<>();
        byUnit.forEach((code, v) -> {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("uomCode", code.isEmpty() ? null : code);
            v.forEach((k, q) -> u.put(k, JsNumbers.js(q)));
            units.add(u);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("stage", stage);
        out.put("percentDelivered", JsNumbers.js(percent));
        out.put("trips", trips);
        out.put("stages", stages);
        out.put("totals", units);
        out.put("lines", lines);
        out.put("deadline", deadline(orderId));
        out.put("returns", customerReturns(orderId));
        return out;
    }

    /** The order's delivery deadline: the plan's deadline, else the commitment date, else the requested date. */
    private String deadline(String orderId) {
        Map<String, Object> r = db.row("SELECT COALESCE(h.deadline, o.\"commitmentDate\", o.\"requestedDate\") AS d FROM"
                + " \"SalesOrder\" o LEFT JOIN \"SalesHandover\" h ON h.\"organizationId\" = o.\"organizationId\" AND"
                + " h.\"sourceType\" = 'sales_order' AND h.\"sourceId\" = o.id WHERE o.id = ? AND o.\"organizationId\" = ?",
                orderId, org());
        return r == null || r.get("d") == null ? null : r.get("d").toString().substring(0, 10);
    }

    /** Customer returns (RMAs) of the order, newest first, each with its lines. */
    private List<Map<String, Object>> customerReturns(String orderId) {
        List<Map<String, Object>> out = db.rows("SELECT r.id, r.number, r.status, r.reason, r.\"physicalReturn\","
                + " r.outcome, r.\"createdAt\", r.\"receivedAt\", r.\"closedAt\", r.notes FROM \"SalesReturn\" r WHERE"
                + " r.\"organizationId\" = ? AND r.\"orderId\" = ? ORDER BY r.\"createdAt\" DESC", org(), orderId);
        for (Map<String, Object> r : out) {
            r.put("lines", db.rows("SELECT l.\"orderLineId\", l.description, l.quantity, l.\"receivedQty\", l.disposition,"
                    + " u.code AS \"uomCode\" FROM \"SalesReturnLine\" l JOIN \"SalesOrderLine\" ol ON ol.id ="
                    + " l.\"orderLineId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = ol.\"uomId\" WHERE l.\"returnId\" = ?"
                    + " ORDER BY l.description", r.get("id")));
        }
        return out;
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

    /**
     * A weighbridge ticket of this delivery as a PDF view copy (Phase A1): the delivery must be a sales delivery the
     * user may read and the ticket must belong to it (404 otherwise). Does not count as a ticket print.
     */
    @Transactional
    public WeighbridgeTicketService.PrintedTicket weighTicketPdf(String id, String ticketId) {
        require(id);
        return weighTickets.renderCopy(ticketId, id);
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
