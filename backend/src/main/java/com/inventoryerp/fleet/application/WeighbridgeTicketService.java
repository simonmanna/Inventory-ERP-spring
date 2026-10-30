package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.WeighbridgeCalculator.Check;
import com.inventoryerp.fleet.application.WeighbridgeCalculator.Tolerance;
import com.inventoryerp.fleet.application.WeighbridgeCalculator.Weighing;
import com.inventoryerp.fleet.application.WeighbridgeCommands.ExceptionDecision;
import com.inventoryerp.fleet.application.WeighbridgeCommands.ReasonInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.StoredTareInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.TareInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.TicketInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.TicketPatch;
import com.inventoryerp.fleet.application.WeighbridgeCommands.TicketQuery;
import com.inventoryerp.fleet.application.WeighbridgeCommands.VoidInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.WeighInput;
import com.inventoryerp.fleet.application.WeighbridgeStationService.DeviceReading;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.fleet.domain.FleetVehicleTare;
import com.inventoryerp.fleet.domain.WeighbridgeException;
import com.inventoryerp.fleet.domain.WeighbridgeTicket;
import com.inventoryerp.kernel.approvals.ApprovalAdminService;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.procurement.application.GoodsReceiptsService;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Weighbridge tickets (fleet 1.4a, requirements §5.1): register a truck (fleet vehicle or ad-hoc plate), driver,
 * customer / supplier, reference document, product and ordered quantity → first weighing → second weighing (or the
 * vehicle's valid stored tare) → net = gross − tare → comparison with the expected weight (product unit conversion,
 * {@link WeighbridgeUnits}) → tolerance check ({@link WeighbridgeCalculator}) → completed, or an out-of-tolerance
 * exception approved through the kernel approval engine (entity type {@value #APPROVAL_ENTITY}) by someone other than
 * the ticket's operators. Completed tickets are immutable: printing is counted, corrections only by void + reissue.
 * Inbound purchase tickets pre-fill exactly one draft goods-receipt line in procurement.
 *
 * <p><b>Public API (1.4b dispatch wiring, 1.9 reports)</b>: {@link #completedTicket(String)},
 * {@link #completedTicketsForTrip(String)}, {@link #completedTicketsForDeliveryOrder(String)} and the event
 * {@code weighbridge.ticket.completed} (also {@code weighbridge.ticket.voided} when a ticket is voided).
 *
 * <p>Device reads run outside the transaction (the indicator can take seconds); the ticket row is then locked
 * {@code FOR UPDATE} and its state re-checked before the weight is stored, so two captures cannot both land.
 */
@Service
public class WeighbridgeTicketService {

    public static final String APPROVAL_ENTITY = "weighbridge_exception";
    static final String AUDIT = "WeighbridgeTicket";
    static final Set<String> LIVE = Set.of("open", "first_weighed", "exception");

    /** A completed ticket as dispatch / receiving need it: net weight in kg and in the document unit. */
    public record CompletedTicket(String id, String ticketNumber, String subjectType, String direction,
            String stationId, String vehicleId, String registrationPlate, String driverId, String partnerId,
            String salesOrderId, String deliveryOrderId, String tripId, String purchaseOrderId,
            String purchaseOrderLineId, String productId, BigDecimal orderedQty, String orderedUomId,
            BigDecimal grossKg, BigDecimal tareKg, BigDecimal netKg, BigDecimal netQty, String uomId,
            BigDecimal varianceKg, boolean withinTolerance, boolean manual, boolean usedStoredTare,
            Instant completedAt, String goodsReceiptId) {}

    /** A rendered ticket print. */
    public record PrintedTicket(String filename, byte[] content) {}

    private record Capture(BigDecimal kg, String source, String deviceId, String rawFrame, String manualReason,
            String tareId) {}

    private final FleetDb db;
    private final WeighbridgeStationService stations;
    private final WeighbridgeUnits units;
    private final ApprovalsService approvals;
    private final ApprovalAdminService approvalAdmin;
    private final GoodsReceiptsService goodsReceipts;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate tx;
    private final WeighbridgeTripFeed tripFeed;

    public WeighbridgeTicketService(FleetDb db, WeighbridgeStationService stations, WeighbridgeUnits units,
            ApprovalsService approvals, ApprovalAdminService approvalAdmin, GoodsReceiptsService goodsReceipts,
            SequenceService sequence, AuditService audit, EventBus events, PlatformTransactionManager txm,
            WeighbridgeTripFeed tripFeed) {
        this.db = db;
        this.stations = stations;
        this.units = units;
        this.approvals = approvals;
        this.approvalAdmin = approvalAdmin;
        this.goodsReceipts = goodsReceipts;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
        this.tripFeed = tripFeed;
    }

    // ================================================================== queries

    private static final String SELECT = "SELECT t.*, s.code AS \"s_code\", s.name AS \"s_name\", pa.name AS"
            + " \"pa_name\", tp.name AS \"tp_name\", p.code AS \"p_code\", p.name AS \"p_name\", v.code AS \"v_code\","
            + " d.code AS \"d_code\", ou.code AS \"ou_code\", nu.code AS \"nu_code\", po.\"orderNumber\" AS \"po_number\","
            + " fl.code AS \"fl_code\", fl.status AS \"fl_status\", fo.code AS \"fo_code\""
            + " FROM \"WeighbridgeTicket\" t JOIN \"WeighbridgeStation\" s ON s.id = t.\"stationId\""
            + " LEFT JOIN \"Partner\" pa ON pa.id = t.\"partnerId\" AND pa.\"organizationId\" = t.\"organizationId\""
            + " LEFT JOIN \"Partner\" tp ON tp.id = t.\"transporterPartnerId\" AND tp.\"organizationId\" ="
            + " t.\"organizationId\" LEFT JOIN \"Product\" p ON p.id = t.\"productId\" AND p.\"organizationId\" ="
            + " t.\"organizationId\" LEFT JOIN \"FleetVehicle\" v ON v.id = t.\"vehicleId\""
            + " LEFT JOIN \"FleetDriver\" d ON d.id = t.\"driverId\""
            + " LEFT JOIN \"UnitOfMeasure\" ou ON ou.id = t.\"orderedUomId\" LEFT JOIN \"UnitOfMeasure\" nu ON nu.id ="
            + " t.\"netUomId\" LEFT JOIN \"PurchaseOrder\" po ON po.id = t.\"purchaseOrderId\" AND po.\"organizationId\""
            + " = t.\"organizationId\" LEFT JOIN \"FleetLoad\" fl ON fl.id = t.\"tripId\" AND fl.\"organizationId\" ="
            + " t.\"organizationId\" LEFT JOIN \"FleetDeliveryOrder\" fo ON fo.id = t.\"deliveryOrderId\" AND"
            + " fo.\"organizationId\" = t.\"organizationId\"";

    /** The ticket queue: filters, search, newest first, {@code {data, meta}}. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(TicketQuery q) {
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        String queue = q.queue() == null ? "" : q.queue();
        switch (queue) {
            case "" , "all" -> { }
            case "open" -> where.append(" AND t.status = 'open'");
            case "awaiting_second" -> where.append(" AND t.status = 'first_weighed'");
            case "exceptions" -> where.append(" AND t.status = 'exception'");
            case "completed" -> where.append(" AND t.status = 'completed'");
            case "void" -> where.append(" AND t.status = 'void'");
            case "cancelled" -> where.append(" AND t.status = 'cancelled'");
            case "live" -> where.append(" AND t.status IN ('open', 'first_weighed', 'exception')");
            default -> throw ApiException.badRequest("Unknown ticket queue " + queue);
        }
        FleetIncidentService.eq(where, params, "t.\"stationId\"", q.stationId());
        FleetIncidentService.eq(where, params, "t.\"subjectType\"", q.subjectType());
        FleetIncidentService.eq(where, params, "t.\"partnerId\"", q.partnerId());
        FleetIncidentService.eq(where, params, "t.\"productId\"", q.productId());
        FleetIncidentService.eq(where, params, "t.\"vehicleId\"", q.vehicleId());
        if (Boolean.TRUE.equals(q.manual())) {
            where.append(" AND t.\"isManual\"");
        }
        if (Boolean.TRUE.equals(q.outOfTolerance())) {
            where.append(" AND t.\"withinTolerance\" = false");
        }
        FleetIncidentService.range(where, params, "t.\"createdAt\"", q.from(), q.to());
        if (FleetVehicleService.notBlank(q.search())) {
            String like = FleetIncidentService.like(q.search());
            where.append(" AND (t.\"ticketNumber\" ILIKE ? OR t.\"registrationPlate\" ILIKE ? OR t.\"referenceNumber\""
                    + " ILIKE ? OR t.\"driverName\" ILIKE ? OR pa.name ILIKE ? OR p.name ILIKE ?)");
            for (int i = 0; i < 6; i++) {
                params.add(like);
            }
        }
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 25 : Math.min(200, Math.max(1, q.pageSize()));
        long total = db.jdbc().sql("SELECT count(*) FROM \"WeighbridgeTicket\" t LEFT JOIN \"Partner\" pa ON pa.id ="
                + " t.\"partnerId\" AND pa.\"organizationId\" = t.\"organizationId\" LEFT JOIN \"Product\" p ON p.id ="
                + " t.\"productId\" AND p.\"organizationId\" = t.\"organizationId\"" + where).params(params)
                .query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY t.\"createdAt\" DESC, t.\"ticketNumber\" DESC LIMIT ? OFFSET ?").params(pageParams))) {
            rows.add(shape(r));
        }
        return PageResult.of(rows, page, size, total);
    }

    /** Counts per queue (the operator's dashboard tabs). */
    @Transactional(readOnly = true)
    public Map<String, Object> queueCounts(String stationId) {
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        FleetIncidentService.eq(where, params, "\"stationId\"", stationId);
        Map<String, Object> r = db.jdbc().sql("SELECT count(*) FILTER (WHERE status = 'open') AS open,"
                + " count(*) FILTER (WHERE status = 'first_weighed') AS \"awaitingSecond\","
                + " count(*) FILTER (WHERE status = 'exception') AS exceptions,"
                + " count(*) FILTER (WHERE status = 'completed' AND \"completedAt\" >= date_trunc('day', now() AT TIME"
                + " ZONE 'UTC')) AS \"completedToday\" FROM \"WeighbridgeTicket\"" + where).params(params).query()
                .singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        r.forEach((k, v) -> out.put(k, ((Number) v).longValue()));
        return out;
    }

    /** Ticket with names, its exceptions, the reissue chain, the goods receipt and the operators. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql(SELECT + " WHERE t.id = ? AND t.\"organizationId\" = ?")
                .params(id, FleetDb.org()));
        if (r == null) {
            throw ApiException.notFound("Weighbridge ticket not found");
        }
        Map<String, Object> t = shape(r);
        t.put("exceptions", SqlRows.list(WeighbridgeException.class, db.jdbc().sql("SELECT * FROM"
                + " \"WeighbridgeException\" WHERE \"ticketId\" = ? AND \"organizationId\" = ? ORDER BY \"raisedAt\"")
                .params(id, FleetDb.org())));
        t.put("reissuedFrom", ref("SELECT id, \"ticketNumber\" FROM \"WeighbridgeTicket\" WHERE id = ? AND"
                + " \"organizationId\" = ?", r.get("reissuedFromId")));
        t.put("replacedBy", ref("SELECT id, \"ticketNumber\" FROM \"WeighbridgeTicket\" WHERE id = ? AND"
                + " \"organizationId\" = ?", r.get("replacedById")));
        t.put("goodsReceipt", ref("SELECT id, \"receiptNumber\", status::text AS status FROM \"GoodsReceiptNote\" WHERE"
                + " id = ? AND \"organizationId\" = ?", r.get("goodsReceiptId")));
        Set<String> ids = new HashSet<>();
        for (String k : List.of("createdBy", "firstBy", "secondBy", "completedBy", "voidedBy", "cancelledBy",
                "lastPrintedBy")) {
            if (r.get(k) != null) {
                ids.add((String) r.get(k));
            }
        }
        Map<String, Object> users = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> u : SqlRows.list(db.jdbc().sql("SELECT id, concat_ws(' ', \"firstName\","
                    + " \"lastName\") AS name FROM \"User\" WHERE id = ANY(?::text[]) AND \"organizationId\" = ?")
                    .params(ids.toArray(String[]::new), FleetDb.org()))) {
                users.put((String) u.get("id"), u.get("name"));
            }
        }
        t.put("users", users);
        Map<String, Object> devices = new LinkedHashMap<>();
        for (String k : List.of("firstDeviceId", "secondDeviceId")) {
            if (r.get(k) != null) {
                devices.put((String) r.get(k), db.jdbc().sql("SELECT code FROM \"WeighbridgeDevice\" WHERE id = ?")
                        .param(r.get(k)).query(String.class).optional().orElse(null));
            }
        }
        t.put("devices", devices);
        return t;
    }

    private Map<String, Object> ref(String sql, Object id) {
        return id == null ? null : SqlRows.one(db.jdbc().sql(sql).params(id, FleetDb.org()));
    }

    private static Map<String, Object> shape(Map<String, Object> r) {
        Map<String, Object> t = SqlRows.as(WeighbridgeTicket.class, r);
        t.put("station", Obj.of("id", r.get("stationId"), "code", r.get("s_code"), "name", r.get("s_name")));
        t.put("partner", r.get("partnerId") == null ? null : Obj.of("id", r.get("partnerId"), "name", r.get("pa_name")));
        t.put("transporter", r.get("transporterPartnerId") == null ? null : Obj.of("id", r.get("transporterPartnerId"),
                "name", r.get("tp_name")));
        t.put("product", r.get("productId") == null ? null : Obj.of("id", r.get("productId"), "code", r.get("p_code"),
                "name", r.get("p_name")));
        t.put("vehicle", r.get("vehicleId") == null ? null : Obj.of("id", r.get("vehicleId"), "code", r.get("v_code")));
        t.put("driver", r.get("driverId") == null ? null : Obj.of("id", r.get("driverId"), "code", r.get("d_code")));
        t.put("purchaseOrder", r.get("purchaseOrderId") == null ? null : Obj.of("id", r.get("purchaseOrderId"),
                "orderNumber", r.get("po_number")));
        t.put("trip", r.get("tripId") == null ? null : Obj.of("id", r.get("tripId"), "code", r.get("fl_code"), "status",
                r.get("fl_status")));
        t.put("deliveryOrder", r.get("deliveryOrderId") == null ? null : Obj.of("id", r.get("deliveryOrderId"), "code",
                r.get("fo_code")));
        t.put("orderedUomCode", r.get("ou_code"));
        t.put("netUomCode", r.get("nu_code"));
        return t;
    }

    // ================================================================== public API (1.4b / 1.9)

    /** The completed ticket (404 when missing, 400 when not completed). */
    @Transactional(readOnly = true)
    public CompletedTicket completedTicket(String id) {
        Map<String, Object> t = db.require(WeighbridgeTicket.class, id, false, "Weighbridge ticket not found");
        if (!"completed".equals(t.get("status"))) {
            throw ApiException.badRequest("Weighbridge ticket " + t.get("ticketNumber") + " is not completed (it is "
                    + t.get("status") + ")");
        }
        return completed(t);
    }

    /** Completed tickets of a fleet 1.3 trip (load), oldest first. */
    @Transactional(readOnly = true)
    public List<CompletedTicket> completedTicketsForTrip(String tripId) {
        return db.list(WeighbridgeTicket.class, " AND \"tripId\" = ? AND status = 'completed' ORDER BY \"completedAt\"",
                tripId).stream().map(WeighbridgeTicketService::completed).toList();
    }

    /** Completed tickets of a fleet 1.3 delivery order, oldest first. */
    @Transactional(readOnly = true)
    public List<CompletedTicket> completedTicketsForDeliveryOrder(String deliveryOrderId) {
        return db.list(WeighbridgeTicket.class, " AND \"deliveryOrderId\" = ? AND status = 'completed' ORDER BY"
                + " \"completedAt\"", deliveryOrderId).stream().map(WeighbridgeTicketService::completed).toList();
    }

    private static CompletedTicket completed(Map<String, Object> t) {
        return new CompletedTicket((String) t.get("id"), (String) t.get("ticketNumber"), (String) t.get("subjectType"),
                (String) t.get("direction"), (String) t.get("stationId"), (String) t.get("vehicleId"),
                (String) t.get("registrationPlate"), (String) t.get("driverId"), (String) t.get("partnerId"),
                (String) t.get("salesOrderId"), (String) t.get("deliveryOrderId"), (String) t.get("tripId"),
                (String) t.get("purchaseOrderId"), (String) t.get("purchaseOrderLineId"), (String) t.get("productId"),
                (BigDecimal) t.get("orderedQty"), (String) t.get("orderedUomId"), (BigDecimal) t.get("grossKg"),
                (BigDecimal) t.get("tareKg"), (BigDecimal) t.get("netKg"), (BigDecimal) t.get("netQty"),
                (String) t.get("netUomId"), (BigDecimal) t.get("varianceKg"),
                !Boolean.FALSE.equals(t.get("withinTolerance")), Boolean.TRUE.equals(t.get("isManual")),
                Boolean.TRUE.equals(t.get("usedStoredTare")), (Instant) t.get("completedAt"),
                (String) t.get("goodsReceiptId"));
    }

    // ================================================================== registration

    @Transactional
    public Map<String, Object> create(TicketInput in) {
        Map<String, Object> station = stations.requireStation(in.stationId(), true);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("subjectType", in.subjectType());
        header.put("vehicleId", in.vehicleId());
        header.put("registrationPlate", in.registrationPlate());
        header.put("trailerPlate", in.trailerPlate());
        header.put("driverId", in.driverId());
        header.put("driverName", in.driverName());
        header.put("transporterPartnerId", in.transporterPartnerId());
        header.put("partnerId", in.partnerId());
        header.put("salesOrderId", in.salesOrderId());
        header.put("deliveryOrderId", in.deliveryOrderId());
        header.put("tripId", in.tripId());
        header.put("purchaseOrderId", in.purchaseOrderId());
        header.put("purchaseOrderLineId", in.purchaseOrderLineId());
        header.put("referenceNumber", in.referenceNumber());
        header.put("productId", in.productId());
        header.put("orderedQty", in.orderedQty());
        header.put("orderedUomId", in.orderedUomId());
        header.put("locationId", in.locationId());
        header.put("notes", in.notes());
        tripFeed.applyTripDefaults(header);
        Map<String, Object> data = resolveHeader(header, station, null);
        return insertTicket(station, data, null);
    }

    private Map<String, Object> insertTicket(Map<String, Object> station, Map<String, Object> data,
            String reissuedFromId) {
        assertTruckFree(null, (String) data.get("registrationPlate"));
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        Map<String, Object> row = new LinkedHashMap<>(data);
        row.put("ticketNumber", sequence.next("weighbridge_ticket:" + year, "WBT-" + year + "-", 6));
        row.put("stationId", station.get("id"));
        row.put("status", "open");
        row.put("reissuedFromId", reissuedFromId);
        row.put("createdBy", TenantContext.userId());
        row.put("updatedBy", TenantContext.userId());
        Map<String, Object> created;
        try {
            created = db.insert(WeighbridgeTicket.class, row);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Truck " + data.get("registrationPlate") + " is already on a live weighbridge"
                    + " ticket");
        }
        audit.recordInTx(AuditInput.of(AUDIT, (String) created.get("id"), "create", null, created));
        return get((String) created.get("id"));
    }

    /** Header edits while the ticket is open or awaiting its second weighing. */
    @Transactional
    public Map<String, Object> update(String id, TicketPatch in) {
        Map<String, Object> t = lock(id);
        assertEditable(t);
        Map<String, Object> station = stations.requireStation((String) t.get("stationId"), false);
        Map<String, Object> merged = merge(t, in);
        Map<String, Object> data = resolveHeader(merged, station, id);
        if (!Objects.equals(data.get("registrationPlate"), t.get("registrationPlate"))) {
            assertTruckFree(id, (String) data.get("registrationPlate"));
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after;
        try {
            after = db.update(WeighbridgeTicket.class, id, data);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Truck " + data.get("registrationPlate") + " is already on a live weighbridge"
                    + " ticket");
        }
        audit.recordInTx(AuditInput.of(AUDIT, id, "update", t, after));
        return get(id);
    }

    private static Map<String, Object> merge(Map<String, Object> t, TicketPatch in) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("subjectType", "vehicleId", "registrationPlate", "trailerPlate", "driverId",
                "driverName", "transporterPartnerId", "partnerId", "salesOrderId", "deliveryOrderId", "tripId",
                "purchaseOrderId", "purchaseOrderLineId", "referenceNumber", "productId", "orderedQty", "orderedUomId",
                "locationId", "notes")) {
            m.put(k, t.get(k));
        }
        if (in == null) {
            return m;
        }
        List<String> clear = WeighbridgeStationService.clearList(in.clear(), WeighbridgeCommands.TICKET_CLEARABLE);
        putIf(m, "subjectType", in.subjectType());
        if (FleetVehicleService.notBlank(in.vehicleId())) {
            m.put("vehicleId", in.vehicleId());
            m.put("registrationPlate", null);
        } else if (FleetVehicleService.notBlank(in.registrationPlate())) {
            m.put("vehicleId", null);
            m.put("registrationPlate", in.registrationPlate());
        }
        putIf(m, "trailerPlate", in.trailerPlate());
        putIf(m, "driverId", in.driverId());
        putIf(m, "driverName", in.driverName());
        putIf(m, "transporterPartnerId", in.transporterPartnerId());
        putIf(m, "partnerId", in.partnerId());
        putIf(m, "salesOrderId", in.salesOrderId());
        putIf(m, "deliveryOrderId", in.deliveryOrderId());
        putIf(m, "tripId", in.tripId());
        putIf(m, "purchaseOrderId", in.purchaseOrderId());
        putIf(m, "purchaseOrderLineId", in.purchaseOrderLineId());
        putIf(m, "referenceNumber", in.referenceNumber());
        putIf(m, "productId", in.productId());
        putIf(m, "orderedQty", in.orderedQty());
        putIf(m, "orderedUomId", in.orderedUomId());
        putIf(m, "locationId", in.locationId());
        putIf(m, "notes", in.notes());
        for (String f : clear) {
            m.put(f, null);
        }
        return m;
    }

    private static void putIf(Map<String, Object> m, String k, Object v) {
        if (v != null && !(v instanceof String s && s.isBlank())) {
            m.put(k, v);
        }
    }

    /**
     * Validates and normalises a ticket header against the referenced documents; returns the columns to store
     * (direction, plate, defaults from the purchase order, expected kg).
     */
    private Map<String, Object> resolveHeader(Map<String, Object> h, Map<String, Object> station, String ticketId) {
        String subject = str(h.get("subjectType"));
        if (subject == null || !WeighbridgeStationService.SUBJECT_TYPES.contains(subject)) {
            throw ApiException.badRequest("Subject type must be one of: "
                    + String.join(", ", WeighbridgeStationService.SUBJECT_TYPES));
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("subjectType", subject);
        d.put("direction", switch (subject) {
            case "sale_delivery" -> "outbound";
            case "purchase_receipt" -> "inbound";
            default -> "internal";
        });
        // Truck
        String vehicleId = str(h.get("vehicleId"));
        String plate;
        if (vehicleId != null) {
            Map<String, Object> v = db.byId(FleetVehicle.class, vehicleId, false);
            if (v == null) {
                throw ApiException.badRequest("Vehicle not found");
            }
            if ("disposed".equals(v.get("status"))) {
                throw ApiException.badRequest("Vehicle " + v.get("code") + " is disposed");
            }
            plate = FleetVehicleService.normalizePlate(v.get("registrationPlate") != null
                    ? (String) v.get("registrationPlate") : (String) v.get("code"));
        } else {
            plate = FleetVehicleService.normalizePlate(str(h.get("registrationPlate")));
            if (plate == null) {
                throw ApiException.badRequest("Choose a fleet vehicle or enter the truck's registration plate");
            }
            // An ad-hoc plate that is a fleet vehicle links to it.
            vehicleId = db.jdbc().sql("SELECT id FROM \"FleetVehicle\" WHERE \"organizationId\" = ? AND"
                    + " \"registrationPlate\" = ? AND \"deletedAt\" IS NULL").params(FleetDb.org(), plate)
                    .query(String.class).optional().orElse(null);
        }
        d.put("vehicleId", vehicleId);
        d.put("registrationPlate", plate);
        d.put("trailerPlate", FleetVehicleService.normalizePlate(str(h.get("trailerPlate"))));
        String driverId = str(h.get("driverId"));
        if (driverId != null && !db.exists("FleetDriver", driverId, true)) {
            throw ApiException.badRequest("Driver not found");
        }
        d.put("driverId", driverId);
        d.put("driverName", FleetVehicleService.trim(str(h.get("driverName"))));
        String transporter = str(h.get("transporterPartnerId"));
        if (transporter != null && !db.exists("Partner", transporter, true)) {
            throw ApiException.badRequest("Transporter not found");
        }
        d.put("transporterPartnerId", transporter);
        // Documents
        String partnerId = str(h.get("partnerId"));
        String salesOrderId = str(h.get("salesOrderId"));
        String deliveryOrderId = FleetVehicleService.trim(str(h.get("deliveryOrderId")));
        String purchaseOrderId = str(h.get("purchaseOrderId"));
        String poLineId = str(h.get("purchaseOrderLineId"));
        String productId = str(h.get("productId"));
        String uomId = str(h.get("orderedUomId"));
        BigDecimal orderedQty = (BigDecimal) h.get("orderedQty");
        if (!"sale_delivery".equals(subject) && (salesOrderId != null || deliveryOrderId != null
                || str(h.get("tripId")) != null)) {
            throw ApiException.badRequest("Only sale deliveries reference a sales order or delivery order");
        }
        if (salesOrderId != null && !db.exists("Order", salesOrderId, false)) {
            throw ApiException.badRequest("Sales order not found");
        }
        if ("purchase_receipt".equals(subject)) {
            if (purchaseOrderId == null) {
                throw ApiException.badRequest("Inbound purchase tickets need a purchase order");
            }
            Map<String, Object> po = SqlRows.one(db.jdbc().sql("SELECT id, \"orderNumber\", \"partnerId\","
                    + " status::text AS status FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"deletedAt\" IS NULL").params(purchaseOrderId, FleetDb.org()));
            if (po == null) {
                throw ApiException.badRequest("Purchase order not found");
            }
            if (!Set.of("active", "partially_received").contains(po.get("status"))) {
                throw ApiException.badRequest("Purchase order " + po.get("orderNumber") + " is " + po.get("status")
                        + " and cannot receive goods");
            }
            List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("SELECT id, \"productId\","
                    + " \"unitOfMeasureId\" FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ? AND"
                    + " \"organizationId\" = ? ORDER BY \"lineNumber\"").params(purchaseOrderId, FleetDb.org()));
            Map<String, Object> line = null;
            if (poLineId != null) {
                line = lines.stream().filter(l -> poLineId.equals(l.get("id"))).findFirst().orElseThrow(
                        () -> ApiException.badRequest("The purchase order line does not belong to purchase order "
                                + po.get("orderNumber")));
            } else if (lines.size() == 1) {
                line = lines.getFirst();
            } else if (productId != null) {
                String pid = productId;
                line = lines.stream().filter(l -> pid.equals(l.get("productId"))).findFirst().orElse(null);
            }
            if (line == null) {
                throw ApiException.badRequest("Choose the line of purchase order " + po.get("orderNumber")
                        + " that is being delivered");
            }
            if (partnerId != null && !partnerId.equals(po.get("partnerId"))) {
                throw ApiException.badRequest("The supplier differs from the supplier of purchase order "
                        + po.get("orderNumber"));
            }
            partnerId = (String) po.get("partnerId");
            if (productId != null && line.get("productId") != null && !productId.equals(line.get("productId"))) {
                throw ApiException.badRequest("The product differs from the product of the purchase order line");
            }
            productId = (String) line.get("productId");
            if (uomId == null) {
                uomId = (String) line.get("unitOfMeasureId");
            }
            d.put("purchaseOrderLineId", line.get("id"));
        } else {
            if (purchaseOrderId != null || poLineId != null) {
                throw ApiException.badRequest("Only purchase receipts reference a purchase order");
            }
            d.put("purchaseOrderLineId", null);
        }
        d.put("purchaseOrderId", purchaseOrderId);
        if (partnerId != null && !db.exists("Partner", partnerId, true)) {
            throw ApiException.badRequest("Customer / supplier not found");
        }
        d.put("partnerId", partnerId);
        d.put("salesOrderId", salesOrderId);
        d.put("deliveryOrderId", deliveryOrderId);
        d.put("tripId", FleetVehicleService.trim(str(h.get("tripId"))));
        d.put("referenceNumber", FleetVehicleService.trim(str(h.get("referenceNumber"))));
        Map<String, Object> product = null;
        if (productId != null) {
            product = SqlRows.one(db.jdbc().sql("SELECT id, name, \"uomId\", \"salesUomId\", \"purchaseUomId\" FROM"
                    + " \"Product\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(productId, FleetDb.org()));
            if (product == null) {
                throw ApiException.badRequest("Product not found");
            }
        }
        d.put("productId", productId);
        if (uomId == null && orderedQty != null && product != null) {
            uomId = str("inbound".equals(d.get("direction")) ? product.get("purchaseUomId") : product.get("salesUomId"));
            if (uomId == null) {
                uomId = str(product.get("uomId"));
            }
        }
        if (orderedQty != null && uomId == null) {
            throw ApiException.badRequest("The ordered quantity needs a unit");
        }
        if (uomId != null && units.unit(uomId) == null) {
            throw ApiException.badRequest("Unit of measure not found");
        }
        d.put("orderedQty", orderedQty);
        d.put("orderedUomId", uomId);
        d.put("expectedKg", orderedQty == null ? null
                : WeighbridgeUnits.toKg(orderedQty, units.requireKgPerUnit(productId, uomId)));
        String locationId = str(h.get("locationId"));
        if (locationId == null && "inbound".equals(d.get("direction"))) {
            locationId = (String) station.get("locationId");
        }
        if (locationId != null && !db.exists("InventoryLocation", locationId, true)) {
            throw ApiException.badRequest("Inventory location not found");
        }
        d.put("locationId", locationId);
        d.put("notes", FleetVehicleService.trim(str(h.get("notes"))));
        tripFeed.validateLink(d, ticketId);
        return d;
    }

    private void assertTruckFree(String exceptId, String plate) {
        Optional<String> live = db.jdbc().sql("SELECT \"ticketNumber\" FROM \"WeighbridgeTicket\" WHERE"
                + " \"organizationId\" = ? AND \"registrationPlate\" = ? AND status IN ('open', 'first_weighed',"
                + " 'exception')" + (exceptId == null ? "" : " AND id <> ?")).params(exceptId == null
                        ? List.of(FleetDb.org(), plate) : List.of(FleetDb.org(), plate, exceptId))
                .query(String.class).optional();
        if (live.isPresent()) {
            throw ApiException.conflict("Truck " + plate + " is already on live weighbridge ticket " + live.get());
        }
    }

    /** Cancel a ticket that is not completed (the truck left without a valid weighing). */
    @Transactional
    public Map<String, Object> cancel(String id, ReasonInput in) {
        Map<String, Object> t = lock(id);
        if (!Set.of("open", "first_weighed").contains(t.get("status"))) {
            throw ApiException.badRequest("Only open tickets or tickets awaiting the second weighing can be cancelled"
                    + " (ticket " + t.get("ticketNumber") + " is " + t.get("status") + ")");
        }
        Map<String, Object> after = db.update(WeighbridgeTicket.class, id, Obj.of("status", "cancelled",
                "cancelledAt", Instant.now(), "cancelledBy", TenantContext.userId(), "cancelReason",
                in.reason().trim(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT, id, "cancel", t, after));
        return get(id);
    }

    // ================================================================== weighing

    /** First weighing (device or manual). */
    public Map<String, Object> firstWeigh(String id, WeighInput in) {
        return capture(id, "first", in);
    }

    /** Second weighing (device or manual): computes net, checks tolerance, completes or raises an exception. */
    public Map<String, Object> secondWeigh(String id, WeighInput in) {
        return capture(id, "second", in);
    }

    private Map<String, Object> capture(String id, String slot, WeighInput in) {
        Map<String, Object> pre = tx.execute(st -> db.require(WeighbridgeTicket.class, id, false,
                "Weighbridge ticket not found"));
        assertSlot(pre, slot);
        // Outside the transaction: the indicator may take seconds.
        Capture c = read(in, (String) pre.get("stationId"));
        return tx.execute(st -> {
            Map<String, Object> t = lock(id);
            assertSlot(t, slot);
            Map<String, Object> station = stations.requireStation((String) t.get("stationId"), false);
            assertPlausible(c.kg(), station);
            store(t, slot, c);
            if ("first".equals(slot)) {
                return get(id);
            }
            complete(id, Boolean.TRUE.equals(in.saveTare()));
            return get(id);
        });
    }

    /** Use the vehicle's valid stored tare as one of the two weighings (single-weigh ticket). */
    @Transactional
    public Map<String, Object> useStoredTare(String id, StoredTareInput in) {
        Map<String, Object> t = lock(id);
        String status = (String) t.get("status");
        if (!Set.of("open", "first_weighed").contains(status)) {
            throw ApiException.badRequest("A stored tare can only be used before the ticket is completed (ticket "
                    + t.get("ticketNumber") + " is " + status + ")");
        }
        if ("stored_tare".equals(t.get("firstSource"))) {
            throw ApiException.badRequest("The ticket already uses a stored tare");
        }
        String plate = (String) t.get("registrationPlate");
        Map<String, Object> tare;
        if (in != null && FleetVehicleService.notBlank(in.tareId())) {
            tare = db.require(FleetVehicleTare.class, in.tareId(), false, "Stored tare not found");
            if (!plate.equals(tare.get("registrationPlate"))) {
                throw ApiException.badRequest("Stored tare of " + tare.get("registrationPlate")
                        + " belongs to another truck");
            }
        } else {
            List<Map<String, Object>> rows = db.list(FleetVehicleTare.class, " AND \"registrationPlate\" = ? AND status"
                    + " = 'active'", plate);
            if (rows.isEmpty()) {
                throw ApiException.notFound("Truck " + plate + " has no stored tare: weigh it empty");
            }
            tare = rows.getFirst();
        }
        Instant now = Instant.now();
        String state = WeighbridgeCalculator.tareState((String) tare.get("status"), (Instant) tare.get("expiresAt"),
                now);
        if ("expired".equals(state)) {
            throw ApiException.badRequest("The stored tare of " + plate + " expired on " + tare.get("expiresAt")
                    + ": weigh the empty truck again (re-tare)");
        }
        if (!"valid".equals(state)) {
            throw ApiException.badRequest("The stored tare of " + plate + " is " + state + " and cannot be used");
        }
        String slot = "open".equals(status) ? "first" : "second";
        store(t, slot, new Capture((BigDecimal) tare.get("tareKg"), "stored_tare", null, null, null,
                (String) tare.get("id")));
        if ("second".equals(slot)) {
            complete(id, false);
        }
        return get(id);
    }

    private Capture read(WeighInput in, String stationId) {
        boolean device = in != null && FleetVehicleService.notBlank(in.deviceId());
        boolean manual = in != null && in.manualKg() != null;
        if (device && manual) {
            throw ApiException.badRequest("Capture the weight from a device or enter it manually, not both");
        }
        if (device) {
            DeviceReading r = stations.readDevice(in.deviceId(), stationId);
            return new Capture(r.weight().kilograms(), "device", in.deviceId(), r.weight().rawFrame(), null, null);
        }
        if (manual) {
            assertManualAllowed();
            String reason = FleetVehicleService.trim(in.manualReason());
            if (reason == null) {
                throw ApiException.badRequest("A reason is required for a manually entered weight");
            }
            return new Capture(in.manualKg(), "manual", null, null, reason, null);
        }
        throw ApiException.badRequest("Capture the weight from a device or enter it manually");
    }

    private static void assertManualAllowed() {
        if (!TenantContext.has(Permissions.WEIGHBRIDGE__MANUAL_ENTRY)) {
            throw ApiException.forbidden("Entering a weight manually requires the weighbridge:manual_entry"
                    + " permission");
        }
    }

    private static void assertSlot(Map<String, Object> t, String slot) {
        String status = (String) t.get("status");
        if ("first".equals(slot) && !"open".equals(status)) {
            throw "first_weighed".equals(status)
                    ? ApiException.conflict("The first weight of ticket " + t.get("ticketNumber")
                            + " is already captured: capture the second weight")
                    : immutableOrState(t);
        }
        if ("second".equals(slot) && !"first_weighed".equals(status)) {
            throw "open".equals(status)
                    ? ApiException.badRequest("Capture the first weight of ticket " + t.get("ticketNumber") + " first")
                    : immutableOrState(t);
        }
    }

    private static ApiException immutableOrState(Map<String, Object> t) {
        String status = (String) t.get("status");
        if ("completed".equals(status) || "void".equals(status)) {
            return ApiException.conflict("Ticket " + t.get("ticketNumber") + " is " + status
                    + " and cannot be changed: void and reissue it to correct it");
        }
        return ApiException.badRequest("Ticket " + t.get("ticketNumber") + " is " + status.replace('_', ' '));
    }

    private static void assertEditable(Map<String, Object> t) {
        if (!Set.of("open", "first_weighed").contains(t.get("status"))) {
            throw immutableOrState(t);
        }
    }

    private static void assertPlausible(BigDecimal kg, Map<String, Object> station) {
        BigDecimal min = SqlRows.dec(station, "minWeightKg");
        BigDecimal max = SqlRows.dec(station, "maxWeightKg");
        if (kg.compareTo(min) < 0) {
            throw ApiException.badRequest("Weight " + plain(kg) + " kg is below the station minimum of " + plain(min)
                    + " kg (is the truck on the platform?)");
        }
        if (kg.compareTo(max) > 0) {
            throw ApiException.badRequest("Weight " + plain(kg) + " kg is above the station maximum of " + plain(max)
                    + " kg");
        }
    }

    private void store(Map<String, Object> t, String slot, Capture c) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(slot + "WeightKg", c.kg());
        data.put(slot + "WeighedAt", Instant.now());
        data.put(slot + "Source", c.source());
        data.put(slot + "DeviceId", c.deviceId());
        data.put(slot + "RawFrame", c.rawFrame());
        data.put(slot + "ManualReason", c.manualReason());
        data.put(slot + "By", TenantContext.userId());
        data.put(slot + "TareId", c.tareId());
        if ("first".equals(slot)) {
            data.put("status", "first_weighed");
        }
        if ("manual".equals(c.source())) {
            data.put("isManual", true);
        }
        if ("stored_tare".equals(c.source())) {
            data.put("usedStoredTare", true);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(WeighbridgeTicket.class, (String) t.get("id"), data);
        audit.recordInTx(AuditInput.of(AUDIT, (String) t.get("id"), "measure", t, after));
    }

    /** Net, units, tolerance → completed or exception. Inside the caller's transaction; ticket locked. */
    private void complete(String id, boolean saveTare) {
        Map<String, Object> t = db.require(WeighbridgeTicket.class, id, true, "Weighbridge ticket not found");
        BigDecimal first = (BigDecimal) t.get("firstWeightKg");
        BigDecimal second = (BigDecimal) t.get("secondWeightKg");
        Weighing w = WeighbridgeCalculator.net(first, second);
        String tareSlot = first.compareTo(second) < 0 ? "first" : "second";
        String grossSlot = "first".equals(tareSlot) ? "second" : "first";
        if ("stored_tare".equals(t.get(grossSlot + "Source"))) {
            throw ApiException.badRequest("The stored tare (" + plain(SqlRows.dec(t, grossSlot + "WeightKg"))
                    + " kg) is heavier than the weighed truck (" + plain(SqlRows.dec(t, tareSlot + "WeightKg"))
                    + " kg): weigh the truck loaded, or weigh it empty again");
        }
        Map<String, Object> station = stations.requireStation((String) t.get("stationId"), false);
        String productId = (String) t.get("productId");
        Map<String, Object> setting = stations.productSetting(productId);
        Tolerance tol = WeighbridgeCalculator.effective(setting == null ? null : (BigDecimal) setting.get(
                "tolerancePct"), setting == null ? null : (BigDecimal) setting.get("toleranceKg"),
                (BigDecimal) station.get("tolerancePct"), (BigDecimal) station.get("toleranceKg"));
        Check check = WeighbridgeCalculator.check(w.netKg(), (BigDecimal) t.get("expectedKg"), tol);
        String netUom = (String) t.get("orderedUomId");
        if (netUom == null && productId != null) {
            netUom = db.jdbc().sql("SELECT \"uomId\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(productId, FleetDb.org()).query(String.class).optional().orElse(null);
        }
        BigDecimal netQty = null;
        if (netUom != null) {
            BigDecimal perUnit = t.get("orderedUomId") != null ? units.requireKgPerUnit(productId, netUom)
                    : units.kgPerUnit(productId, netUom);
            if (perUnit != null && perUnit.signum() > 0) {
                netQty = WeighbridgeUnits.fromKg(w.netKg(), perUnit);
            } else {
                netUom = null;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("grossKg", w.grossKg());
        data.put("tareKg", w.tareKg());
        data.put("netKg", w.netKg());
        data.put("netQty", netQty);
        data.put("netUomId", netUom);
        data.put("varianceKg", check.varianceKg());
        data.put("variancePct", check.variancePct());
        data.put("allowedVarianceKg", check.allowedKg());
        data.put("tolerancePct", tol.pct());
        data.put("toleranceKg", tol.kg());
        data.put("withinTolerance", check.within());
        Map<String, Object> after = db.update(WeighbridgeTicket.class, id, data);
        if (saveTare && !"stored_tare".equals(t.get(tareSlot + "Source"))) {
            saveTareFromTicket(after, tareSlot, station);
        }
        if (check.within()) {
            finishCompletion(id);
        } else {
            raiseException(after, check);
        }
    }

    private void finishCompletion(String id) {
        Map<String, Object> t = db.require(WeighbridgeTicket.class, id, true, "Weighbridge ticket not found");
        Map<String, Object> after = db.update(WeighbridgeTicket.class, id, Obj.of("status", "completed",
                "completedAt", Instant.now(), "completedBy", TenantContext.userId(), "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT, id, "post", t, after));
        if ("purchase_receipt".equals(after.get("subjectType")) && after.get("locationId") != null) {
            after = feedGoodsReceipt(after, (String) after.get("locationId"));
        }
        after = tripFeed.onCompleted(after);
        events.publish(DomainEvents.WEIGHBRIDGE_TICKET_COMPLETED, completedPayload(after));
    }

    private static Map<String, Object> completedPayload(Map<String, Object> t) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("organizationId", t.get("organizationId"));
        p.put("ticketId", t.get("id"));
        p.put("ticketNumber", t.get("ticketNumber"));
        p.put("subjectType", t.get("subjectType"));
        p.put("direction", t.get("direction"));
        p.put("stationId", t.get("stationId"));
        p.put("vehicleId", t.get("vehicleId"));
        p.put("registrationPlate", t.get("registrationPlate"));
        p.put("partnerId", t.get("partnerId"));
        p.put("productId", t.get("productId"));
        p.put("salesOrderId", t.get("salesOrderId"));
        p.put("deliveryOrderId", t.get("deliveryOrderId"));
        p.put("tripId", t.get("tripId"));
        p.put("purchaseOrderId", t.get("purchaseOrderId"));
        p.put("grossKg", plainOrNull(t.get("grossKg")));
        p.put("tareKg", plainOrNull(t.get("tareKg")));
        p.put("netKg", plainOrNull(t.get("netKg")));
        p.put("netQty", plainOrNull(t.get("netQty")));
        p.put("uomId", t.get("netUomId"));
        p.put("withinTolerance", !Boolean.FALSE.equals(t.get("withinTolerance")));
        p.put("manual", Boolean.TRUE.equals(t.get("isManual")));
        p.put("reissuedFromId", t.get("reissuedFromId"));
        p.put("goodsReceiptId", t.get("goodsReceiptId"));
        p.put("tripFeedStatus", t.get("tripFeedStatus"));
        return p;
    }

    // ================================================================== exceptions

    private void raiseException(Map<String, Object> t, Check check) {
        String id = (String) t.get("id");
        Map<String, Object> e = db.insert(WeighbridgeException.class, Obj.of(
                "ticketId", id,
                "kind", "tolerance",
                "expectedKg", check.expectedKg(),
                "netKg", t.get("netKg"),
                "varianceKg", check.varianceKg(),
                "variancePct", check.variancePct(),
                "allowedVarianceKg", check.allowedKg(),
                "status", "pending",
                "raisedBy", TenantContext.userId()));
        db.update(WeighbridgeTicket.class, id, Obj.of("status", "exception", "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("WeighbridgeException", (String) e.get("id"), "create", null, e));
        ensureWorkflow();
        Map<String, Object> gate = approvals.checkOrRequestApproval(APPROVAL_ENTITY, (String) e.get("id"), Obj.of(
                "amount", check.varianceKg().abs().toPlainString(), "ticket", t.get("ticketNumber"), "varianceKg",
                check.varianceKg().toPlainString(), "allowedKg", check.allowedKg().toPlainString(), "plate",
                t.get("registrationPlate")));
        if (gate != null && gate.get("requestId") != null) {
            db.update(WeighbridgeException.class, (String) e.get("id"), Obj.of("approvalRequestId",
                    gate.get("requestId")));
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("organizationId", FleetDb.org());
        ev.put("exceptionId", e.get("id"));
        ev.put("ticketId", id);
        ev.put("ticketNumber", t.get("ticketNumber"));
        ev.put("varianceKg", check.varianceKg().toPlainString());
        ev.put("variancePct", check.variancePct() == null ? null : check.variancePct().toPlainString());
        ev.put("allowedVarianceKg", check.allowedKg().toPlainString());
        events.publish(DomainEvents.WEIGHBRIDGE_EXCEPTION_RAISED, ev);
    }

    /** Creates the default workflow when the organization has none (active or not). */
    private void ensureWorkflow() {
        long n = db.jdbc().sql("SELECT count(*) FROM \"ApprovalWorkflow\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = ?").params(FleetDb.org(), APPROVAL_ENTITY).query(Long.class).single();
        if (n == 0) {
            approvalAdmin.createWorkflow("Weighbridge exception", APPROVAL_ENTITY, null, true, true,
                    List.of(new ApprovalAdminService.Step(1, "Weighbridge supervisor",
                            List.of(Permissions.WEIGHBRIDGE__APPROVE_EXCEPTION), 1, null, null)));
        }
    }

    /** Exceptions (newest first) with their ticket. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> exceptions(String status, String stationId, Integer page,
            Integer pageSize) {
        StringBuilder where = new StringBuilder(" WHERE e.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        FleetIncidentService.eq(where, params, "e.status", status);
        FleetIncidentService.eq(where, params, "t.\"stationId\"", stationId);
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 25 : Math.min(200, Math.max(1, pageSize));
        long total = db.jdbc().sql("SELECT count(*) FROM \"WeighbridgeException\" e JOIN \"WeighbridgeTicket\" t ON"
                + " t.id = e.\"ticketId\"" + where).params(params).query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT e.*, t.\"ticketNumber\" AS \"t_number\","
                + " t.\"registrationPlate\" AS \"t_plate\", t.status AS \"t_status\", t.\"isManual\" AS \"t_manual\","
                + " t.\"firstBy\" AS \"t_first\", t.\"secondBy\" AS \"t_second\", t.\"createdBy\" AS \"t_created\","
                + " t.\"orderedQty\" AS \"t_qty\", ou.code AS \"t_uom\", pr.name AS \"t_product\", pa.name AS"
                + " \"t_partner\", s.code AS \"t_station\" FROM \"WeighbridgeException\" e JOIN \"WeighbridgeTicket\" t"
                + " ON t.id = e.\"ticketId\" JOIN \"WeighbridgeStation\" s ON s.id = t.\"stationId\" LEFT JOIN"
                + " \"UnitOfMeasure\" ou ON ou.id = t.\"orderedUomId\" LEFT JOIN \"Product\" pr ON pr.id ="
                + " t.\"productId\" LEFT JOIN \"Partner\" pa ON pa.id = t.\"partnerId\"" + where
                + " ORDER BY e.\"raisedAt\" DESC LIMIT ? OFFSET ?").params(pp))) {
            Map<String, Object> e = SqlRows.as(WeighbridgeException.class, r);
            Set<String> operators = new HashSet<>();
            for (String k : List.of("t_first", "t_second", "t_created")) {
                if (r.get(k) != null) {
                    operators.add((String) r.get(k));
                }
            }
            if (e.get("raisedBy") != null) {
                operators.add((String) e.get("raisedBy"));
            }
            e.put("ticket", Obj.of("id", r.get("ticketId"), "ticketNumber", r.get("t_number"), "registrationPlate",
                    r.get("t_plate"), "status", r.get("t_status"), "isManual", r.get("t_manual"), "orderedQty",
                    r.get("t_qty"), "orderedUomCode", r.get("t_uom"), "productName", r.get("t_product"),
                    "partnerName", r.get("t_partner"), "stationCode", r.get("t_station")));
            e.put("ownTicket", operators.contains(TenantContext.userId()));
            rows.add(e);
        }
        return PageResult.of(rows, p, size, total);
    }

    @Transactional
    public Map<String, Object> approveException(String exceptionId, ExceptionDecision in) {
        return decideByUser(exceptionId, true, in == null ? null : FleetVehicleService.trim(in.notes()));
    }

    @Transactional
    public Map<String, Object> rejectException(String exceptionId, ExceptionDecision in) {
        String notes = in == null ? null : FleetVehicleService.trim(in.notes());
        if (notes == null) {
            throw ApiException.badRequest("A reason is required to reject a weighbridge exception");
        }
        return decideByUser(exceptionId, false, notes);
    }

    private Map<String, Object> decideByUser(String exceptionId, boolean approved, String notes) {
        Map<String, Object> e = db.require(WeighbridgeException.class, exceptionId, true,
                "Weighbridge exception not found");
        if (!"pending".equals(e.get("status"))) {
            throw ApiException.badRequest("The exception is already " + e.get("status"));
        }
        Map<String, Object> t = lock((String) e.get("ticketId"));
        String actor = TenantContext.userId();
        if (operators(t, e).contains(actor)) {
            throw ApiException.forbidden("A weighbridge operator cannot approve or reject the exception of their own"
                    + " ticket");
        }
        String requestId = (String) e.get("approvalRequestId");
        if (requestId != null) {
            approvals.decide(requestId, approved ? "approved" : "rejected", notes);
            String status = requestStatus(requestId);
            if ("pending".equals(status)) {
                return get((String) t.get("id"));
            }
            approved = "approved".equals(status);
        }
        applyException(e, approved, actor, notes);
        return get((String) t.get("id"));
    }

    /** Applies an approval-engine decision (from {@code approval.decided}); idempotent, separation of duties kept. */
    @Transactional
    public Map<String, Object> applyDecision(String exceptionId, boolean approved, String deciderId) {
        Map<String, Object> e = db.require(WeighbridgeException.class, exceptionId, true,
                "Weighbridge exception not found");
        if (!"pending".equals(e.get("status"))) {
            return e;
        }
        Map<String, Object> t = lock((String) e.get("ticketId"));
        if (deciderId != null && operators(t, e).contains(deciderId)) {
            // The engine let a ticket operator decide (e.g. the first-weigh operator): not accepted. The exception
            // stays pending and must be decided by someone else through the weighbridge exception screen.
            Map<String, Object> after = db.update(WeighbridgeException.class, exceptionId, Obj.of("approvalRequestId",
                    null, "decisionNotes", "A decision by an operator of the ticket was not accepted (separation of"
                            + " duties); another approver must decide"));
            audit.recordInTx(AuditInput.of("WeighbridgeException", exceptionId, "update", e, after));
            return after;
        }
        applyException(e, approved, deciderId, null);
        return db.require(WeighbridgeException.class, exceptionId, false, "Weighbridge exception not found");
    }

    /** Re-read the exception's approval request and apply its outcome now; 400 while pending. */
    @Transactional
    public Map<String, Object> syncException(String exceptionId) {
        Map<String, Object> e = db.require(WeighbridgeException.class, exceptionId, false,
                "Weighbridge exception not found");
        if (!"pending".equals(e.get("status"))) {
            return get((String) e.get("ticketId"));
        }
        String requestId = (String) e.get("approvalRequestId");
        if (requestId == null) {
            throw ApiException.badRequest("The exception has no approval request: approve or reject it here");
        }
        String status = requestStatus(requestId);
        if ("pending".equals(status)) {
            throw ApiException.badRequest("The exception is waiting for approval (request " + requestId + ")");
        }
        applyDecision(exceptionId, "approved".equals(status), lastApprover(requestId));
        return get((String) e.get("ticketId"));
    }

    private void applyException(Map<String, Object> e, boolean approved, String deciderId, String notes) {
        String exceptionId = (String) e.get("id");
        String ticketId = (String) e.get("ticketId");
        Map<String, Object> after = db.update(WeighbridgeException.class, exceptionId, Obj.of("status",
                approved ? "approved" : "rejected", "decidedBy", deciderId, "decidedAt", Instant.now(),
                "decisionNotes", notes));
        audit.recordInTx(AuditInput.of("WeighbridgeException", exceptionId, approved ? "approve" : "reject", e,
                after));
        if (approved) {
            finishCompletion(ticketId);
            return;
        }
        // Rejected: the second weighing is discarded; the load is corrected and the truck weighed again.
        Map<String, Object> t = db.require(WeighbridgeTicket.class, ticketId, true, "Weighbridge ticket not found");
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : List.of("secondWeightKg", "secondWeighedAt", "secondSource", "secondDeviceId",
                "secondRawFrame", "secondManualReason", "secondBy", "secondTareId", "grossKg", "tareKg", "netKg",
                "netQty", "netUomId", "varianceKg", "variancePct", "allowedVarianceKg", "withinTolerance")) {
            data.put(k, null);
        }
        data.put("status", "first_weighed");
        data.put("isManual", "manual".equals(t.get("firstSource")));
        data.put("usedStoredTare", "stored_tare".equals(t.get("firstSource")));
        data.put("updatedBy", deciderId);
        Map<String, Object> reopened = db.update(WeighbridgeTicket.class, ticketId, data);
        audit.recordInTx(AuditInput.of(AUDIT, ticketId, "reopen", t, reopened));
    }

    private static Set<String> operators(Map<String, Object> t, Map<String, Object> e) {
        Set<String> s = new HashSet<>();
        for (Object o : new Object[] {t.get("createdBy"), t.get("firstBy"), t.get("secondBy"), e.get("raisedBy")}) {
            if (o != null) {
                s.add((String) o);
            }
        }
        return s;
    }

    private String requestStatus(String requestId) {
        return db.jdbc().sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ? AND \"organizationId\" = ?")
                .params(requestId, FleetDb.org()).query(String.class).optional().orElse("pending");
    }

    String lastApprover(String requestId) {
        return db.jdbc().sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
    }

    // ================================================================== print, void + reissue

    /** Prints a completed (or void) ticket: counts the copy; the first print is the original. */
    @Transactional
    public PrintedTicket print(String id) {
        Map<String, Object> t = lock(id);
        if (!Set.of("completed", "void").contains(t.get("status"))) {
            throw ApiException.badRequest("Only completed tickets can be printed (ticket " + t.get("ticketNumber")
                    + " is " + String.valueOf(t.get("status")).replace('_', ' ') + ")");
        }
        int copy = ((Number) t.get("printCount")).intValue() + 1;
        Instant now = Instant.now();
        Map<String, Object> after = db.update(WeighbridgeTicket.class, id, Obj.of("printCount", copy,
                "firstPrintedAt", t.get("firstPrintedAt") == null ? now : t.get("firstPrintedAt"), "lastPrintedAt",
                now, "lastPrintedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT, id, "reprint", Obj.of("printCount", t.get("printCount")),
                Obj.of("printCount", after.get("printCount"))));
        String company = db.jdbc().sql("SELECT name FROM \"Organization\" WHERE id = ?").param(FleetDb.org())
                .query(String.class).optional().orElse("");
        return new PrintedTicket(t.get("ticketNumber") + ".pdf", WeighbridgeTicketPdf.render(get(id), company, copy));
    }

    /**
     * Void a completed ticket (reason, audited). A goods-receipt draft it pre-filled is cancelled; a posted receipt
     * must be reversed in procurement first. {@code reissue} creates the replacement ticket with the corrected header
     * and — unless {@code copyWeights} is false — the same weighings (re-evaluated: completed or exception).
     */
    @Transactional
    public Map<String, Object> voidTicket(String id, VoidInput in) {
        Map<String, Object> t = lock(id);
        if (!"completed".equals(t.get("status"))) {
            throw ApiException.badRequest("Only completed tickets can be voided (ticket " + t.get("ticketNumber")
                    + " is " + String.valueOf(t.get("status")).replace('_', ' ') + "); cancel an unfinished ticket"
                    + " instead");
        }
        String reason = in.reason().trim();
        if (t.get("goodsReceiptId") != null) {
            Map<String, Object> grn = SqlRows.one(db.jdbc().sql("SELECT \"receiptNumber\", status::text AS status FROM"
                    + " \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ?").params(t.get("goodsReceiptId"),
                    FleetDb.org()));
            if (grn != null && "posted".equals(grn.get("status"))) {
                throw ApiException.conflict("Ticket " + t.get("ticketNumber") + " fed goods receipt "
                        + grn.get("receiptNumber") + ", which is posted: reverse that receipt in procurement first");
            }
            if (grn != null && "draft".equals(grn.get("status"))) {
                goodsReceipts.cancelDraft((String) t.get("goodsReceiptId"), "Weighbridge ticket " + t.get(
                        "ticketNumber") + " voided: " + reason);
            }
        }
        tripFeed.onVoiding(t, reason);
        Map<String, Object> voided = db.update(WeighbridgeTicket.class, id, Obj.of("status", "void", "voidedAt",
                Instant.now(), "voidedBy", TenantContext.userId(), "voidReason", reason, "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT, id, "cancel", t, voided));
        String replacementId = null;
        if (Boolean.TRUE.equals(in.reissue())) {
            Map<String, Object> station = stations.requireStation((String) t.get("stationId"), false);
            Map<String, Object> data = resolveHeader(merge(t, in.corrections()), station, null);
            Map<String, Object> created = insertTicket(station, data, id);
            replacementId = (String) created.get("id");
            if (!Boolean.FALSE.equals(in.copyWeights())) {
                Map<String, Object> fresh = db.require(WeighbridgeTicket.class, replacementId, true,
                        "Weighbridge ticket not found");
                Map<String, Object> copy = new LinkedHashMap<>();
                for (String slot : List.of("first", "second")) {
                    for (String f : List.of("WeightKg", "WeighedAt", "Source", "DeviceId", "RawFrame", "ManualReason",
                            "By", "TareId")) {
                        copy.put(slot + f, t.get(slot + f));
                    }
                }
                copy.put("isManual", t.get("isManual"));
                copy.put("usedStoredTare", t.get("usedStoredTare"));
                copy.put("status", "first_weighed");
                Map<String, Object> after = db.update(WeighbridgeTicket.class, replacementId, copy);
                audit.recordInTx(AuditInput.of(AUDIT, replacementId, "update", fresh, after));
                complete(replacementId, false);
            }
            db.update(WeighbridgeTicket.class, id, Obj.of("replacedById", replacementId));
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("organizationId", FleetDb.org());
        ev.put("ticketId", id);
        ev.put("ticketNumber", t.get("ticketNumber"));
        ev.put("reason", reason);
        ev.put("replacedById", replacementId);
        ev.put("deliveryOrderId", t.get("deliveryOrderId"));
        ev.put("tripId", t.get("tripId"));
        events.publish(DomainEvents.WEIGHBRIDGE_TICKET_VOIDED, ev);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("voided", get(id));
        out.put("reissued", replacementId == null ? null : get(replacementId));
        return out;
    }

    /**
     * Feed the ticket's trip (again) — e.g. after its product got a load factor; 400 with the reason when the trip
     * cannot take the weight (already dispatched, no weight factor). Idempotent.
     */
    @Transactional
    public Map<String, Object> feedTrip(String id) {
        Map<String, Object> t = lock(id);
        tripFeed.feedTrip(t);
        return get(id);
    }

    // ================================================================== inbound → goods receipt

    /**
     * Pre-fills the procurement goods receipt of a completed inbound ticket: one DRAFT receipt with one line (the PO
     * line, net quantity in the PO line's unit, PO price) that the store posts through procurement's own approval /
     * GL flow. Idempotent: a ticket that already fed a receipt returns it ({@code created: false}).
     */
    @Transactional
    public Map<String, Object> goodsReceipt(String id, String locationId) {
        Map<String, Object> t = lock(id);
        if (!"purchase_receipt".equals(t.get("subjectType"))) {
            throw ApiException.badRequest("Only inbound purchase tickets feed a goods receipt");
        }
        if (!"completed".equals(t.get("status"))) {
            throw ApiException.badRequest("Ticket " + t.get("ticketNumber") + " is not completed (it is "
                    + String.valueOf(t.get("status")).replace('_', ' ') + ")");
        }
        if (t.get("goodsReceiptId") != null) {
            return Obj.of("created", false, "ticket", get(id));
        }
        String loc = FleetVehicleService.notBlank(locationId) ? locationId : (String) t.get("locationId");
        if (loc == null) {
            throw ApiException.badRequest("Choose the store that receives the goods");
        }
        if (!db.exists("InventoryLocation", loc, true)) {
            throw ApiException.badRequest("Inventory location not found");
        }
        if (!loc.equals(t.get("locationId"))) {
            t = db.update(WeighbridgeTicket.class, id, Obj.of("locationId", loc));
        }
        feedGoodsReceipt(t, loc);
        return Obj.of("created", true, "ticket", get(id));
    }

    private Map<String, Object> feedGoodsReceipt(Map<String, Object> t, String locationId) {
        Map<String, Object> line = SqlRows.one(db.jdbc().sql("SELECT l.id, l.\"productId\", l.\"unitOfMeasureId\","
                + " l.\"unitPrice\", l.description, p.name AS \"productName\", p.\"uomId\" AS \"baseUomId\","
                + " p.\"purchaseUomId\" FROM \"PurchaseOrderLine\" l LEFT JOIN \"Product\" p ON p.id = l.\"productId\""
                + " WHERE l.id = ? AND l.\"organizationId\" = ?").params(t.get("purchaseOrderLineId"), FleetDb.org()));
        if (line == null) {
            throw ApiException.badRequest("The purchase order line of ticket " + t.get("ticketNumber") + " is gone");
        }
        String unit = str(line.get("unitOfMeasureId"));
        if (unit == null) {
            unit = str(line.get("purchaseUomId")) != null ? str(line.get("purchaseUomId")) : str(line.get("baseUomId"));
        }
        BigDecimal qty = unit == null ? (BigDecimal) t.get("netKg")
                : WeighbridgeUnits.fromKg((BigDecimal) t.get("netKg"),
                        units.requireKgPerUnit((String) line.get("productId"), unit));
        String branchId = db.jdbc().sql("SELECT \"branchId\" FROM \"WeighbridgeStation\" WHERE id = ?")
                .param(t.get("stationId")).query(String.class).optional().orElse(null);
        Map<String, Object> grnLine = new LinkedHashMap<>();
        grnLine.put("purchaseOrderLineId", line.get("id"));
        grnLine.put("productId", line.get("productId"));
        grnLine.put("description", (line.get("productName") != null ? line.get("productName") : line.get(
                "description")) + " — weighbridge ticket " + t.get("ticketNumber"));
        grnLine.put("quantity", qty);
        grnLine.put("unitCost", line.get("unitPrice") == null ? BigDecimal.ZERO : line.get("unitPrice"));
        grnLine.put("notes", "Net " + plain((BigDecimal) t.get("netKg")) + " kg (gross " + plain((BigDecimal)
                t.get("grossKg")) + " kg, tare " + plain((BigDecimal) t.get("tareKg")) + " kg)");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("purchaseOrderId", t.get("purchaseOrderId"));
        input.put("partnerId", t.get("partnerId"));
        input.put("branchId", branchId);
        input.put("warehouseId", locationId);
        input.put("receivedAt", ((Instant) (t.get("completedAt") == null ? Instant.now() : t.get("completedAt")))
                .toString());
        input.put("notes", "Weighbridge ticket " + t.get("ticketNumber") + ", truck " + t.get("registrationPlate")
                + (t.get("referenceNumber") == null ? "" : ", delivery note " + t.get("referenceNumber")));
        input.put("lines", List.of(grnLine));
        Map<String, Object> grn = goodsReceipts.createDraft(input);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) grn.get("lines");
        Map<String, Object> after = db.update(WeighbridgeTicket.class, (String) t.get("id"), Obj.of("goodsReceiptId",
                grn.get("id"), "goodsReceiptLineId", lines.getFirst().get("id")));
        audit.recordInTx(AuditInput.of(AUDIT, (String) t.get("id"), "receive", null, Obj.of("goodsReceiptId",
                grn.get("id"), "receiptNumber", grn.get("receiptNumber"), "quantity", qty.toPlainString())));
        return after;
    }

    // ================================================================== stored tares

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> tares(String vehicleId, String plate, String state, Integer page,
            Integer pageSize) {
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        FleetIncidentService.eq(where, params, "r.\"vehicleId\"", vehicleId);
        if (FleetVehicleService.notBlank(plate)) {
            where.append(" AND r.\"registrationPlate\" ILIKE ?");
            params.add(FleetIncidentService.like(plate));
        }
        String s = state == null || state.isBlank() ? "current" : state;
        switch (s) {
            case "current" -> where.append(" AND r.status = 'active'");
            case "valid" -> where.append(" AND r.status = 'active' AND r.\"expiresAt\" > now() AT TIME ZONE 'UTC'");
            case "expired" -> where.append(" AND r.status = 'active' AND r.\"expiresAt\" <= now() AT TIME ZONE 'UTC'");
            case "all" -> { }
            default -> throw ApiException.badRequest("Unknown tare state " + s);
        }
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 25 : Math.min(200, Math.max(1, pageSize));
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetVehicleTare\" r" + where).params(params)
                .query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        Instant now = Instant.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT r.*, v.code AS \"v_code\", s.code AS"
                + " \"s_code\", dv.code AS \"d_code\" FROM \"FleetVehicleTare\" r LEFT JOIN \"FleetVehicle\" v ON v.id ="
                + " r.\"vehicleId\" LEFT JOIN \"WeighbridgeStation\" s ON s.id = r.\"stationId\" LEFT JOIN"
                + " \"WeighbridgeDevice\" dv ON dv.id = r.\"deviceId\"" + where
                + " ORDER BY r.\"weighedAt\" DESC LIMIT ? OFFSET ?").params(pp))) {
            Map<String, Object> tare = SqlRows.as(FleetVehicleTare.class, r);
            tare.put("state", WeighbridgeCalculator.tareState((String) r.get("status"), (Instant) tare.get(
                    "expiresAt"), now));
            tare.put("vehicleCode", r.get("v_code"));
            tare.put("stationCode", r.get("s_code"));
            tare.put("deviceCode", r.get("d_code"));
            rows.add(tare);
        }
        return PageResult.of(rows, p, size, total);
    }

    /** Weigh an empty truck and store its tare (supersedes the previous one of the plate). */
    public Map<String, Object> createTare(TareInput in) {
        Map<String, Object> station = tx.execute(st -> stations.requireStation(in.stationId(), true));
        Capture c = read(new WeighInput(in.deviceId(), in.manualKg(), in.manualReason(), null, null),
                (String) station.get("id"));
        return tx.execute(st -> {
            String vehicleId = FleetIncidentService.blank(in.vehicleId());
            String plate;
            if (vehicleId != null) {
                Map<String, Object> v = db.byId(FleetVehicle.class, vehicleId, false);
                if (v == null) {
                    throw ApiException.badRequest("Vehicle not found");
                }
                plate = FleetVehicleService.normalizePlate(v.get("registrationPlate") != null
                        ? (String) v.get("registrationPlate") : (String) v.get("code"));
            } else {
                plate = FleetVehicleService.normalizePlate(in.registrationPlate());
                if (plate == null) {
                    throw ApiException.badRequest("Choose a fleet vehicle or enter the truck's registration plate");
                }
                vehicleId = db.jdbc().sql("SELECT id FROM \"FleetVehicle\" WHERE \"organizationId\" = ? AND"
                        + " \"registrationPlate\" = ? AND \"deletedAt\" IS NULL").params(FleetDb.org(), plate)
                        .query(String.class).optional().orElse(null);
            }
            assertPlausible(c.kg(), station);
            Map<String, Object> tare = storeTare(vehicleId, plate, c, station, null);
            return tare;
        });
    }

    private void saveTareFromTicket(Map<String, Object> t, String tareSlot, Map<String, Object> station) {
        Capture c = new Capture((BigDecimal) t.get(tareSlot + "WeightKg"), (String) t.get(tareSlot + "Source"),
                (String) t.get(tareSlot + "DeviceId"), (String) t.get(tareSlot + "RawFrame"),
                (String) t.get(tareSlot + "ManualReason"), null);
        storeTare((String) t.get("vehicleId"), (String) t.get("registrationPlate"), c, station, (String) t.get("id"));
    }

    private Map<String, Object> storeTare(String vehicleId, String plate, Capture c, Map<String, Object> station,
            String ticketId) {
        for (Map<String, Object> old : SqlRows.list(FleetVehicleTare.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetVehicleTare\" WHERE \"organizationId\" = ? AND \"registrationPlate\" = ? AND status ="
                + " 'active' FOR UPDATE").params(FleetDb.org(), plate))) {
            Map<String, Object> after = db.update(FleetVehicleTare.class, (String) old.get("id"), Obj.of("status",
                    "superseded"));
            audit.recordInTx(AuditInput.of("FleetVehicleTare", (String) old.get("id"), "update", old, after));
        }
        Instant now = Instant.now();
        Map<String, Object> row = db.insert(FleetVehicleTare.class, Obj.of(
                "vehicleId", vehicleId,
                "registrationPlate", plate,
                "tareKg", c.kg(),
                "weighedAt", now,
                "expiresAt", WeighbridgeCalculator.tareExpiresAt(now, ((Number) station.get("tareValidityDays"))
                        .intValue()),
                "stationId", station.get("id"),
                "deviceId", c.deviceId(),
                "source", c.source(),
                "rawFrame", c.rawFrame(),
                "manualReason", c.manualReason(),
                "ticketId", ticketId,
                "status", "active",
                "createdBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetVehicleTare", (String) row.get("id"), "create", null, row));
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("state", WeighbridgeCalculator.tareState("active", (Instant) row.get("expiresAt"), now));
        return out;
    }

    @Transactional
    public Map<String, Object> revokeTare(String id, ReasonInput in) {
        Map<String, Object> tare = db.require(FleetVehicleTare.class, id, true, "Stored tare not found");
        if (!"active".equals(tare.get("status"))) {
            throw ApiException.badRequest("The stored tare is already " + tare.get("status"));
        }
        Map<String, Object> after = db.update(FleetVehicleTare.class, id, Obj.of("status", "revoked",
                "revokedReason", in.reason().trim(), "revokedBy", TenantContext.userId(), "revokedAt",
                Instant.now()));
        audit.recordInTx(AuditInput.of("FleetVehicleTare", id, "cancel", tare, after));
        return after;
    }

    // ================================================================== helpers

    private Map<String, Object> lock(String id) {
        return db.require(WeighbridgeTicket.class, id, true, "Weighbridge ticket not found");
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static String plainOrNull(Object v) {
        return v instanceof BigDecimal b ? b.toPlainString() : null;
    }
}
