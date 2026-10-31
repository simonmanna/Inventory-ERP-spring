package com.inventoryerp.fleet.application;

import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.fleet.application.DispatchCommands.AssignInput;
import com.inventoryerp.fleet.application.DispatchCommands.CloseTripInput;
import com.inventoryerp.fleet.application.DispatchCommands.LoadInput;
import com.inventoryerp.fleet.application.DispatchCommands.LoadLineInput;
import com.inventoryerp.fleet.application.DispatchCommands.LoadedLineInput;
import com.inventoryerp.fleet.application.DispatchCommands.PlanInput;
import com.inventoryerp.fleet.application.DispatchCommands.PodInput;
import com.inventoryerp.fleet.application.DispatchCommands.PodLineInput;
import com.inventoryerp.fleet.application.DispatchCommands.ReturnInput;
import com.inventoryerp.fleet.application.DispatchCommands.TripPatch;
import com.inventoryerp.fleet.application.DispatchCommands.TripQuery;
import com.inventoryerp.fleet.application.DriverEligibilityPolicy.VehicleFacts;
import com.inventoryerp.fleet.domain.FleetDeliveryOrderLine;
import com.inventoryerp.fleet.domain.FleetLoad;
import com.inventoryerp.fleet.domain.FleetLoadLine;
import com.inventoryerp.fleet.domain.FleetProofOfDelivery;
import com.inventoryerp.fleet.domain.FleetTransporterRate;
import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads / trips of delivery orders (fleet 1.3, ADR-S009 §3): planning within payload and body volume
 * ({@link LoadPlanner}), assignment of an own truck + driver (+ trailer) — eligibility through
 * {@link DriverEligibilityPolicy} and {@link FleetDispatchEligibilityService}, capacity against the planned / weighed
 * tonnage — or a third-party transporter with an agreed freight rate; then the lifecycle of {@link TripStatusPolicy}.
 *
 * <p><b>Stock</b>: dispatch issues each line from the trip's yard location through {@link StockService#issue} (product,
 * batch, line unit with its snapshot factor; Dr COGS / Cr stock by the inventory posting rules) <b>exactly once per
 * load</b>: the delivery order and then the trip row are locked {@code FOR UPDATE}, the status must still be open and
 * {@code stockIssuedAt} empty, so a replayed or concurrent dispatch cannot issue twice (the route also carries an
 * Idempotency-Key). Returned quantities (POD) and returned loads are received back at the issue cost
 * ({@code receiveReturn}: Dr stock / Cr COGS). Delivery orders in stock mode {@code invoiced} move no stock at dispatch.
 *
 * <p><b>Lock order</b>: delivery order → trip → vehicle → driver (the 1.2 vehicle-then-driver rule).
 *
 * <p>Public API for 1.4b (weighbridge) and 1.9 (costing): {@link #recordWeighedQuantity}, {@link #get},
 * {@link #require}.
 */
@Service
public class FleetDispatchService {

    static final String AUDIT_ENTITY = "FleetLoad";
    static final String STOCK_SOURCE = "fleet_trip";
    static final String OWNER_TYPE = "fleet_trip";
    private static final Pattern DATA_URL = Pattern.compile("^data:image/(png|jpeg);base64,([A-Za-z0-9+/=\\s]+)$");

    private final FleetDb db;
    private final FleetDeliveryOrderService orders;
    private final FleetDiscrepancyService discrepancies;
    private final FleetVehicleService vehicles;
    private final FleetDriverService drivers;
    private final FleetDispatchEligibilityService eligibility;
    private final FleetMeterService meters;
    private final FleetTelematicsService telematics;
    private final FleetLoadFactors factors;
    private final StockService stock;
    private final PosStockLocation stockLocations;
    private final FilesService files;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private final FleetWeighbridgeRuleService weighRules;

    private final CreditService credit;

    public FleetDispatchService(FleetDb db, FleetDeliveryOrderService orders, FleetDiscrepancyService discrepancies,
            FleetVehicleService vehicles, FleetDriverService drivers, FleetDispatchEligibilityService eligibility,
            FleetMeterService meters, FleetTelematicsService telematics, FleetLoadFactors factors, StockService stock,
            PosStockLocation stockLocations, FilesService files, SequenceService sequence, AuditService audit,
            EventBus events, FleetWeighbridgeRuleService weighRules, CreditService credit) {
        this.credit = credit;
        this.db = db;
        this.orders = orders;
        this.discrepancies = discrepancies;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.eligibility = eligibility;
        this.meters = meters;
        this.telematics = telematics;
        this.factors = factors;
        this.stock = stock;
        this.stockLocations = stockLocations;
        this.files = files;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
        this.weighRules = weighRules;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(TripQuery q) {
        int p = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 25 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(q.search())) {
            String like = "%" + q.search().trim().replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (t.code ILIKE ? OR o.code ILIKE ? OR p.name ILIKE ? OR v.\"registrationPlate\" ILIKE ?"
                    + " OR t.\"transporterPlate\" ILIKE ? OR t.\"deliveryNoteNumber\" ILIKE ?)");
            for (int i = 0; i < 6; i++) {
                params.add(like);
            }
        }
        if (FleetVehicleService.notBlank(q.status())) {
            switch (q.status()) {
                case "open" -> where.append(" AND t.status IN ('planned', 'loading', 'weighed')");
                case "on_road" -> where.append(" AND t.status IN ('dispatched', 'in_transit')");
                case "active" -> where.append(" AND t.status IN ('planned', 'loading', 'weighed', 'dispatched',"
                        + " 'in_transit')");
                default -> {
                    where.append(" AND t.status = ?");
                    params.add(q.status());
                }
            }
        }
        if (q.dateFrom() != null) {
            where.append(" AND t.\"plannedDate\" >= ?");
            params.add(java.sql.Date.valueOf(q.dateFrom()));
        }
        if (q.dateTo() != null) {
            where.append(" AND t.\"plannedDate\" <= ?");
            params.add(java.sql.Date.valueOf(q.dateTo()));
        }
        eq(where, params, "t.\"vehicleId\"", q.vehicleId());
        eq(where, params, "t.\"driverId\"", q.driverId());
        eq(where, params, "t.\"deliveryOrderId\"", q.deliveryOrderId());
        eq(where, params, "o.\"partnerId\"", q.partnerId());
        if (Boolean.TRUE.equals(q.mine())) {
            where.append(" AND e.\"userId\" = ?");
            params.add(TenantContext.userId());
        }
        String from = " FROM \"FleetLoad\" t JOIN \"FleetDeliveryOrder\" o ON o.id = t.\"deliveryOrderId\" LEFT JOIN"
                + " \"Partner\" p ON p.id = o.\"partnerId\" LEFT JOIN \"FleetVehicle\" v ON v.id = t.\"vehicleId\" LEFT"
                + " JOIN \"FleetDriver\" d ON d.id = t.\"driverId\" LEFT JOIN \"HrEmployee\" e ON e.id = d.\"employeeId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        String order = Boolean.TRUE.equals(q.mine()) ? " ORDER BY CASE WHEN t.status IN ('dispatched', 'in_transit')"
                + " THEN 0 WHEN t.status IN ('planned', 'loading', 'weighed') THEN 1 ELSE 2 END, t.\"plannedDate\","
                + " t.code" : " ORDER BY t.\"plannedDate\" DESC, t.code DESC";
        return PageResult.of(SqlRows.list(db.jdbc().sql(FleetDeliveryOrderService.TRIP_SELECT + where + order
                + " LIMIT ? OFFSET ?").params(pp)), p, size, total);
    }

    /** Trip with lines, delivery order + site, assignment, proof of delivery (signed URLs) and discrepancies. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> t = SqlRows.one(db.jdbc().sql(FleetDeliveryOrderService.TRIP_SELECT + " WHERE t.id = ? AND"
                + " t.\"organizationId\" = ?").params(id, FleetDb.org()));
        if (t == null) {
            throw ApiException.notFound("Trip not found");
        }
        t.put("lines", SqlRows.list(db.jdbc().sql("SELECT l.*, p.code AS \"productCode\", p.name AS \"productName\","
                + " p.\"trackInventory\", u.code AS \"uomCode\", ol.\"orderedQty\" AS \"orderedQty\", ol.\"lineNumber\""
                + " FROM \"FleetLoadLine\" l JOIN \"Product\" p ON p.id = l.\"productId\" JOIN \"FleetDeliveryOrderLine\""
                + " ol ON ol.id = l.\"deliveryOrderLineId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE"
                + " l.\"organizationId\" = ? AND l.\"loadId\" = ? ORDER BY ol.\"lineNumber\", l.id")
                .params(FleetDb.org(), id)));
        Map<String, Object> o = SqlRows.one(db.jdbc().sql(FleetDeliveryOrderService.SELECT + " WHERE o.id = ? AND"
                + " o.\"organizationId\" = ?").params(t.get("deliveryOrderId"), FleetDb.org()));
        if (o != null && o.get("siteId") != null) {
            o.put("site", SqlRows.one(db.jdbc().sql("SELECT * FROM \"FleetCustomerSite\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(o.get("siteId"), FleetDb.org())));
        }
        t.put("deliveryOrder", o);
        Map<String, Object> pod = SqlRows.one(FleetProofOfDelivery.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetProofOfDelivery\" WHERE \"organizationId\" = ? AND \"loadId\" = ?").params(FleetDb.org(), id));
        if (pod != null) {
            pod.put("signatureUrl", url((String) pod.get("signatureFileId")));
            pod.put("photoUrl", url((String) pod.get("photoFileId")));
        }
        t.put("pod", pod);
        t.put("discrepancies", SqlRows.list(db.jdbc().sql("SELECT x.*, pr.name AS \"productName\" FROM"
                + " \"FleetDeliveryDiscrepancy\" x JOIN \"Product\" pr ON pr.id = x.\"productId\" WHERE"
                + " x.\"organizationId\" = ? AND x.\"loadId\" = ? ORDER BY x.\"createdAt\"").params(FleetDb.org(), id)));
        t.put("weighbridgeTickets", FleetWeighbridgeRuleService.linkedTickets(db, "tripId", id));
        Map<String, Object> wb = weighRules.forTrip(t).toMap();
        Map<String, Object> live = weighRules.liveTicket(id);
        wb.put("liveTicket", live);
        wb.put("fedByTicket", weighRules.fedByCompletedTicket(t));
        t.put("weighbridge", wb);
        return t;
    }

    /** The trip row (404). */
    public Map<String, Object> require(String id, boolean lock) {
        return db.require(FleetLoad.class, id, lock, "Trip not found");
    }

    private String url(String fileId) {
        if (fileId == null) {
            return null;
        }
        Object u = files.signDownload(fileId).get("url");
        return u == null ? null : u.toString();
    }

    // ------------------------------------------------------------------ planning

    /** Proposed split of the remaining quantity (nothing saved). */
    @Transactional(readOnly = true)
    public Map<String, Object> previewPlan(String deliveryOrderId, PlanInput in) {
        Map<String, Object> order = orders.require(deliveryOrderId, false);
        FleetDeliveryOrderService.assertEditable(order);
        Planning pl = planning(deliveryOrderId, in);
        List<Map<String, Object>> loads = new ArrayList<>();
        for (LoadPlanner.PlannedLoad l : pl.plan().loads()) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (LoadPlanner.PlannedLine x : l.lines()) {
                Map<String, Object> dl = pl.lines().get(x.lineId());
                lines.add(Obj.of("deliveryOrderLineId", x.lineId(), "productId", dl.get("productId"), "productName",
                        dl.get("productName"), "uomCode", dl.get("uomCode"), "quantity", x.quantity()));
            }
            loads.add(Obj.of("lines", lines, "tonnes", l.tonnes(), "m3", l.m3()));
        }
        return Obj.of("loads", loads, "warnings", pl.plan().warnings(), "capacityT", pl.capacity().payloadT(),
                "capacityM3", pl.capacity().volumeM3());
    }

    /** Split the remaining quantity into loads; with a vehicle they are assigned to it (and the driver). */
    @Transactional
    public Map<String, Object> plan(String deliveryOrderId, PlanInput in) {
        Map<String, Object> order = orders.require(deliveryOrderId, true);
        FleetDeliveryOrderService.assertEditable(order);
        Planning pl = planning(deliveryOrderId, in);
        LocalDate date = in.plannedDate() != null ? in.plannedDate() : (LocalDate) order.get("requestedDate");
        List<String> created = new ArrayList<>();
        for (LoadPlanner.PlannedLoad l : pl.plan().loads()) {
            List<PlannedQty> qtys = new ArrayList<>();
            for (LoadPlanner.PlannedLine x : l.lines()) {
                Map<String, Object> dl = pl.lines().get(x.lineId());
                qtys.add(new PlannedQty(dl, x.quantity(), (String) dl.get("batchNumber")));
            }
            Map<String, Object> trip = createLoad(order, date, qtys, pl.capacity(), null, null);
            created.add((String) trip.get("id"));
            if (FleetVehicleService.notBlank(in.vehicleId())) {
                assignOwn(trip, in.vehicleId(), FleetDeliveryOrderService.blank(in.trailerId()),
                        FleetDeliveryOrderService.blank(in.driverId()));
            }
        }
        orders.refreshStatus(deliveryOrderId);
        Map<String, Object> out = orders.get(deliveryOrderId);
        out.put("createdTripIds", created);
        out.put("warnings", pl.plan().warnings());
        return out;
    }

    /** Add one load with explicit quantities. */
    @Transactional
    public Map<String, Object> addLoad(String deliveryOrderId, LoadInput in) {
        Map<String, Object> order = orders.require(deliveryOrderId, true);
        FleetDeliveryOrderService.assertEditable(order);
        Map<String, Map<String, Object>> lines = new HashMap<>();
        for (Map<String, Object> l : orders.lines(deliveryOrderId)) {
            lines.put((String) l.get("id"), l);
        }
        List<PlannedQty> qtys = new ArrayList<>();
        Map<String, BigDecimal> asked = new HashMap<>();
        for (LoadLineInput li : in.lines()) {
            Map<String, Object> dl = lines.get(li.deliveryOrderLineId());
            if (dl == null) {
                throw ApiException.badRequest("Delivery order line not found");
            }
            BigDecimal total = asked.merge(li.deliveryOrderLineId(), li.quantity(), BigDecimal::add);
            BigDecimal remaining = SqlRows.dec(dl, "remainingQty");
            if (total.compareTo(remaining) > 0) {
                throw ApiException.badRequest("Only " + JsNumbers.dec(remaining) + " " + unit(dl) + " of "
                        + dl.get("productName") + " remain to be planned");
            }
            qtys.add(new PlannedQty(dl, li.quantity(), li.batchNumber() != null ? li.batchNumber()
                    : (String) dl.get("batchNumber")));
        }
        LocalDate date = in.plannedDate() != null ? in.plannedDate() : (LocalDate) order.get("requestedDate");
        Map<String, Object> trip = createLoad(order, date, qtys, null, in.purpose(), in.notes());
        orders.refreshStatus(deliveryOrderId);
        return get((String) trip.get("id"));
    }

    private record PlannedQty(Map<String, Object> line, BigDecimal qty, String batch) {}

    private record Planning(LoadPlanner.Plan plan, LoadPlanner.Capacity capacity, Map<String, Map<String, Object>> lines) {}

    private Planning planning(String deliveryOrderId, PlanInput in) {
        BigDecimal payload = in.payloadT();
        BigDecimal volume = in.volumeM3();
        if (FleetVehicleService.notBlank(in.vehicleId())) {
            Map<String, Object> v = vehicles.require(in.vehicleId(), false);
            Map<String, Object> tr = FleetVehicleService.notBlank(in.trailerId())
                    ? vehicles.require(in.trailerId(), false) : null;
            if (payload == null) {
                payload = sum((BigDecimal) v.get("payloadCapacityT"), tr == null ? null
                        : (BigDecimal) tr.get("payloadCapacityT"));
            }
            if (volume == null) {
                volume = sum((BigDecimal) v.get("bodyVolumeM3"), tr == null ? null : (BigDecimal) tr.get("bodyVolumeM3"));
            }
        }
        LoadPlanner.Capacity cap = new LoadPlanner.Capacity(payload, volume);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        List<LoadPlanner.Item> items = new ArrayList<>();
        for (Map<String, Object> l : orders.lines(deliveryOrderId)) {
            byId.put((String) l.get("id"), l);
            items.add(new LoadPlanner.Item((String) l.get("id"), (String) l.get("productName"), (String) l.get("uomCode"),
                    SqlRows.dec(l, "remainingQty"), (BigDecimal) l.get("tonnesPerUnit"), (BigDecimal) l.get("m3PerUnit"),
                    factors.step((String) l.get("uomId"))));
        }
        return new Planning(LoadPlanner.plan(items, cap, in.maxLoads()), cap, byId);
    }

    private Map<String, Object> createLoad(Map<String, Object> order, LocalDate date, List<PlannedQty> qtys,
            LoadPlanner.Capacity cap, String purpose, String notes) {
        String orderId = (String) order.get("id");
        int seq = db.jdbc().sql("SELECT COALESCE(MAX(sequence), 0) + 1 FROM \"FleetLoad\" WHERE \"organizationId\" = ?"
                + " AND \"deliveryOrderId\" = ?").params(FleetDb.org(), orderId).query(Integer.class).single();
        BigDecimal tonnes = BigDecimal.ZERO;
        BigDecimal m3 = BigDecimal.ZERO;
        boolean knownT = false;
        boolean knownM = false;
        for (PlannedQty q : qtys) {
            if (q.line().get("tonnesPerUnit") != null) {
                tonnes = tonnes.add(q.qty().multiply((BigDecimal) q.line().get("tonnesPerUnit")));
                knownT = true;
            }
            if (q.line().get("m3PerUnit") != null) {
                m3 = m3.add(q.qty().multiply((BigDecimal) q.line().get("m3PerUnit")));
                knownM = true;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", sequence.next("fleet_trip", "TRP-", 6));
        data.put("deliveryOrderId", orderId);
        data.put("sequence", seq);
        data.put("plannedDate", date);
        data.put("status", "planned");
        data.put("carrier", "own");
        data.put("capacityT", cap == null ? null : cap.payloadT());
        data.put("capacityM3", cap == null ? null : cap.volumeM3());
        data.put("plannedTonnes", knownT ? tonnes.setScale(3, RoundingMode.HALF_UP) : null);
        data.put("plannedM3", knownM ? m3.setScale(3, RoundingMode.HALF_UP) : null);
        data.put("locationId", order.get("locationId"));
        data.put("costCenterId", order.get("costCenterId"));
        data.put("purpose", FleetVehicleService.trim(purpose) != null ? purpose.trim()
                : "Delivery " + order.get("code"));
        data.put("notes", FleetVehicleService.trim(notes));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> trip = db.insert(FleetLoad.class, data);
        for (PlannedQty q : qtys) {
            db.insert(FleetLoadLine.class, Obj.of("loadId", trip.get("id"), "deliveryOrderLineId", q.line().get("id"),
                    "productId", q.line().get("productId"), "uomId", q.line().get("uomId"), "uomFactor",
                    q.line().get("uomFactor"), "plannedQty", q.qty(), "tonnesPerUnit", q.line().get("tonnesPerUnit"),
                    "m3PerUnit", q.line().get("m3PerUnit"), "batchNumber", FleetVehicleService.trim(q.batch())));
            adjustOrderLine((String) q.line().get("id"), q.qty(), null, null, null);
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) trip.get("id"), "create", null, trip));
        return trip;
    }

    /** Adds the given deltas (null = 0) to a delivery-order line's planned / dispatched / delivered / returned. */
    private void adjustOrderLine(String lineId, BigDecimal planned, BigDecimal dispatched, BigDecimal delivered,
            BigDecimal returned) {
        db.jdbc().sql("UPDATE \"FleetDeliveryOrderLine\" SET \"plannedQty\" = GREATEST(\"plannedQty\" + ?, 0),"
                + " \"dispatchedQty\" = \"dispatchedQty\" + ?, \"deliveredQty\" = \"deliveredQty\" + ?, \"returnedQty\" ="
                + " \"returnedQty\" + ?, \"updatedAt\" = " + FleetDb.NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(nz(planned), nz(dispatched), nz(delivered), nz(returned), lineId, FleetDb.org()).update();
    }

    // ------------------------------------------------------------------ edit / assign

    @Transactional
    public Map<String, Object> update(String id, TripPatch in) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        if (!TripStatusPolicy.isOpen((String) t.get("status"))) {
            throw ApiException.badRequest("Trip " + t.get("code") + " is " + TripStatusPolicy.label((String)
                    t.get("status")) + " and cannot be changed");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.plannedDate() != null) {
            data.put("plannedDate", in.plannedDate());
        }
        if (FleetVehicleService.notBlank(in.locationId())) {
            if (stockLocations.resolve(FleetDb.org(), in.locationId()) == null) {
                throw ApiException.badRequest("Location not found");
            }
            data.put("locationId", in.locationId());
        }
        if (FleetVehicleService.notBlank(in.costCenterId())) {
            if (!db.exists("CostCenter", in.costCenterId(), true)) {
                throw ApiException.badRequest("Cost centre not found");
            }
            data.put("costCenterId", in.costCenterId());
        }
        if (in.purpose() != null) {
            data.put("purpose", FleetVehicleService.trim(in.purpose()));
        }
        if (in.notes() != null) {
            data.put("notes", FleetVehicleService.trim(in.notes()));
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetLoad.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", t, after));
        return get(id);
    }

    /** Assign an own truck + driver (+ trailer) or a third-party transporter (before dispatch). */
    @Transactional
    public Map<String, Object> assign(String id, AssignInput in) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        if (!TripStatusPolicy.isOpen((String) t.get("status"))) {
            throw ApiException.badRequest("Trip " + t.get("code") + " is " + TripStatusPolicy.label((String)
                    t.get("status")) + "; its truck can no longer change");
        }
        if ("own".equals(in.carrier())) {
            if (!FleetVehicleService.notBlank(in.vehicleId())) {
                throw ApiException.badRequest("Choose the truck");
            }
            assignOwn(t, in.vehicleId(), FleetDeliveryOrderService.blank(in.trailerId()),
                    FleetDeliveryOrderService.blank(in.driverId()));
        } else {
            assignTransporter(t, in);
        }
        return get(id);
    }

    /** Eligibility + capacity of a candidate assignment, without saving: {@code {eligible, reasons, warnings, …}}. */
    @Transactional(readOnly = true)
    public Map<String, Object> assignmentCheck(String id, String vehicleId, String trailerId, String driverId) {
        Map<String, Object> t = require(id, false);
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        BigDecimal capT = null;
        BigDecimal capM = null;
        if (FleetVehicleService.notBlank(vehicleId)) {
            Map<String, Object> v = vehicles.require(vehicleId, false);
            VehicleFacts vf = drivers.vehicleFacts(vehicleId, false);
            if ("trailer".equals(vf.categoryKind())) {
                reasons.add(vf.code() + " is a trailer; choose the truck that pulls it");
            }
            FleetDispatchEligibilityService.Eligibility e = eligibility.check(vehicleId);
            reasons.addAll(e.reasons());
            warnings.addAll(e.warnings());
            capT = (BigDecimal) v.get("payloadCapacityT");
            capM = (BigDecimal) v.get("bodyVolumeM3");
            if (FleetVehicleService.notBlank(trailerId)) {
                Map<String, Object> tr = vehicles.require(trailerId, false);
                VehicleFacts tf = drivers.vehicleFacts(trailerId, false);
                if (!"trailer".equals(tf.categoryKind())) {
                    reasons.add(tf.code() + " is not a trailer");
                }
                FleetDispatchEligibilityService.Eligibility te = eligibility.check(trailerId);
                reasons.addAll(te.reasons());
                warnings.addAll(te.warnings());
                capT = sum(capT, (BigDecimal) tr.get("payloadCapacityT"));
                capM = sum(capM, (BigDecimal) tr.get("bodyVolumeM3"));
            }
            if (FleetVehicleService.notBlank(driverId)) {
                Map<String, Object> d = drivers.require(driverId, false);
                reasons.addAll(DriverEligibilityPolicy.reasons(drivers.facts(d), vf, (LocalDate) t.get("plannedDate"),
                        "dispatched"));
            }
            String over = overCapacity(t, capT, capM, vf.code());
            if (over != null) {
                reasons.add(over);
            }
        }
        return Obj.of("eligible", reasons.isEmpty(), "reasons", reasons, "warnings", warnings, "capacityT", capT,
                "capacityM3", capM, "plannedTonnes", t.get("plannedTonnes"), "plannedM3", t.get("plannedM3"),
                "loadedTonnes", t.get("loadedTonnes"));
    }

    private void assignOwn(Map<String, Object> t, String vehicleId, String trailerId, String driverId) {
        Map<String, Object> v = vehicles.require(vehicleId, true);
        VehicleFacts vf = drivers.vehicleFacts(vehicleId, false);
        if ("trailer".equals(vf.categoryKind())) {
            throw ApiException.badRequest(vf.code() + " is a trailer; choose the truck that pulls it");
        }
        eligibility.assertEligible(vehicleId);
        BigDecimal capT = (BigDecimal) v.get("payloadCapacityT");
        BigDecimal capM = (BigDecimal) v.get("bodyVolumeM3");
        if (trailerId != null) {
            Map<String, Object> tr = vehicles.require(trailerId, true);
            VehicleFacts tf = drivers.vehicleFacts(trailerId, false);
            if (!"trailer".equals(tf.categoryKind())) {
                throw ApiException.badRequest(tf.code() + " is not a trailer");
            }
            eligibility.assertEligible(trailerId);
            capT = sum(capT, (BigDecimal) tr.get("payloadCapacityT"));
            capM = sum(capM, (BigDecimal) tr.get("bodyVolumeM3"));
        }
        if (driverId != null) {
            Map<String, Object> d = drivers.require(driverId, true);
            DriverEligibilityPolicy.assertEligible(drivers.facts(d), vf, (LocalDate) t.get("plannedDate"), "dispatched");
        }
        String over = overCapacity(t, capT, capM, vf.code());
        if (over != null) {
            throw ApiException.badRequest(over);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("carrier", "own");
        data.put("vehicleId", vehicleId);
        data.put("trailerId", trailerId);
        data.put("driverId", driverId);
        data.put("transporterPartnerId", null);
        data.put("transporterRateId", null);
        data.put("transporterPlate", null);
        data.put("transporterDriverName", null);
        data.put("freightBasis", null);
        data.put("freightRate", null);
        data.put("capacityT", capT);
        data.put("capacityM3", capM);
        if (t.get("costCenterId") == null && v.get("costCenterId") != null) {
            data.put("costCenterId", v.get("costCenterId"));
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetLoad.class, (String) t.get("id"), data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) t.get("id"), "assign", t, after));
    }

    private void assignTransporter(Map<String, Object> t, AssignInput in) {
        String partnerId = FleetDeliveryOrderService.blank(in.transporterPartnerId());
        if (partnerId == null) {
            throw ApiException.badRequest("Choose the transporter");
        }
        Boolean supplier = db.jdbc().sql("SELECT \"isSupplier\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(partnerId, FleetDb.org()).query(Boolean.class).optional()
                .orElse(null);
        if (supplier == null) {
            throw ApiException.badRequest("Transporter not found");
        }
        if (!supplier) {
            throw ApiException.badRequest("The transporter must be a supplier partner");
        }
        String basis = in.freightBasis();
        BigDecimal rate = in.freightRate();
        String rateId = FleetDeliveryOrderService.blank(in.transporterRateId());
        if (rateId != null) {
            Map<String, Object> r = db.require(FleetTransporterRate.class, rateId, false, "Transporter rate not found");
            if (!partnerId.equals(r.get("partnerId")) || !Boolean.TRUE.equals(r.get("isActive"))) {
                throw ApiException.badRequest("Rate " + r.get("name") + " is not an active rate of this transporter");
            }
            basis = (String) r.get("basis");
            rate = (BigDecimal) r.get("rate");
        }
        if (basis == null || rate == null) {
            throw ApiException.badRequest("Give the transporter's agreed freight rate (a rate card or basis + rate)");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("carrier", "transporter");
        data.put("vehicleId", null);
        data.put("trailerId", null);
        data.put("driverId", null);
        data.put("transporterPartnerId", partnerId);
        data.put("transporterRateId", rateId);
        data.put("transporterPlate", FleetVehicleService.trim(in.transporterPlate()) == null ? null
                : in.transporterPlate().trim().toUpperCase());
        data.put("transporterDriverName", FleetVehicleService.trim(in.transporterDriverName()));
        data.put("freightBasis", basis);
        data.put("freightRate", rate);
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetLoad.class, (String) t.get("id"), data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) t.get("id"), "assign", t, after));
    }

    private static String overCapacity(Map<String, Object> t, BigDecimal capT, BigDecimal capM, String vehicleCode) {
        BigDecimal tonnes = t.get("loadedTonnes") != null ? (BigDecimal) t.get("loadedTonnes")
                : (BigDecimal) t.get("plannedTonnes");
        if (capT != null && tonnes != null && tonnes.compareTo(capT) > 0) {
            return "Trip " + t.get("code") + " carries " + JsNumbers.dec(tonnes) + " t but " + vehicleCode + " takes "
                    + JsNumbers.dec(capT) + " t; split the load";
        }
        BigDecimal m3 = (BigDecimal) t.get("plannedM3");
        if (capM != null && m3 != null && m3.compareTo(capM) > 0) {
            return "Trip " + t.get("code") + " fills " + JsNumbers.dec(m3) + " m³ but the body of " + vehicleCode
                    + " holds " + JsNumbers.dec(capM) + " m³; split the load";
        }
        return null;
    }

    // ------------------------------------------------------------------ lifecycle

    /** planned → loading (truck at the loading point); optional start odometer. */
    @Transactional
    public Map<String, Object> startLoading(String id, BigDecimal odometer) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        TripStatusPolicy.assertMove((String) t.get("code"), (String) t.get("status"), "loading");
        assertAssigned(t);
        recheckOwn(t);
        Map<String, Object> data = Obj.of("status", "loading", "loadingStartedAt", Instant.now(), "updatedBy",
                TenantContext.userId());
        data.putAll(startMeter(t, odometer));
        db.update(FleetLoad.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", t, Obj.of("status", "loading")));
        return get(id);
    }

    /**
     * Loaded quantity from an external weighing document — the 1.4b weighbridge ticket calls this with the ticket's net
     * weight. {@code uom} {@code t} / {@code kg}: the net weight is spread over the lines in proportion to their planned
     * tonnage (a line needs a weight factor); otherwise {@code uom} is the line unit id / code and the trip must have one
     * line. Idempotent per ({@code sourceType}, {@code sourceId}); a new document re-weighs while the trip is not yet
     * dispatched. The trip becomes {@code weighed}.
     */
    @Transactional
    public Map<String, Object> recordWeighedQuantity(String tripId, BigDecimal netQty, String uom, String sourceType,
            String sourceId) {
        if (netQty == null || netQty.signum() < 0) {
            throw ApiException.badRequest("The weighed quantity must be zero or more");
        }
        Locked k = lockTrip(tripId);
        Map<String, Object> t = k.trip();
        if (sourceId != null && sourceId.equals(t.get("weighSourceId")) && sourceType != null
                && sourceType.equals(t.get("weighSourceType"))) {
            return get(tripId);
        }
        assertWeighable(t);
        List<Map<String, Object>> lines = lines(tripId);
        BigDecimal perWeigh = FleetLoadFactors.tonnesPerWeighUnit(uom);
        Map<String, BigDecimal> qtyByLine = new LinkedHashMap<>();
        if (perWeigh != null) {
            BigDecimal tonnes = netQty.multiply(perWeigh);
            BigDecimal planned = BigDecimal.ZERO;
            for (Map<String, Object> l : lines) {
                if (l.get("tonnesPerUnit") == null) {
                    throw ApiException.badRequest("No weight factor for a product on trip " + t.get("code")
                            + ": set its load factor or enter the loaded quantity by hand");
                }
                planned = planned.add(SqlRows.dec(l, "plannedQty").multiply(SqlRows.dec(l, "tonnesPerUnit")));
            }
            for (Map<String, Object> l : lines) {
                BigDecimal tpu = SqlRows.dec(l, "tonnesPerUnit");
                BigDecimal share = planned.signum() == 0
                        ? tonnes.divide(BigDecimal.valueOf(lines.size()), 6, RoundingMode.HALF_UP)
                        : tonnes.multiply(SqlRows.dec(l, "plannedQty").multiply(tpu)).divide(planned, 6,
                                RoundingMode.HALF_UP);
                qtyByLine.put((String) l.get("id"), share.divide(tpu, 3, RoundingMode.HALF_UP));
            }
        } else {
            if (lines.size() != 1) {
                throw ApiException.badRequest("Trip " + t.get("code") + " carries several products; weigh it in t or kg");
            }
            Map<String, Object> l = lines.getFirst();
            String code = db.jdbc().sql("SELECT code FROM \"UnitOfMeasure\" WHERE id = ?").param(l.get("uomId"))
                    .query(String.class).optional().orElse(null);
            if (uom != null && !uom.equals(l.get("uomId")) && !uom.equalsIgnoreCase(code)) {
                throw ApiException.badRequest("Unit " + uom + " is neither t, kg nor the line unit of trip "
                        + t.get("code"));
            }
            qtyByLine.put((String) l.get("id"), netQty.setScale(3, RoundingMode.HALF_UP));
        }
        applyLoaded(t, lines, qtyByLine, Map.of());
        db.update(FleetLoad.class, tripId, Obj.of("weighSourceType", sourceType, "weighSourceId", sourceId,
                "weighedNetQty", netQty, "weighedUom", uom));
        return get(tripId);
    }

    /**
     * Undo the loaded quantity a weighing document gave (1.4b: its weighbridge ticket was voided before dispatch): the
     * lines lose their loaded quantity, the trip goes back to {@code loading} and its weigh source is cleared. Only
     * when ({@code sourceType}, {@code sourceId}) is still the trip's weigh source and the trip is open; returns
     * whether anything changed. The caller refuses the void once the trip left (its stock is issued).
     */
    @Transactional
    public boolean clearWeighedQuantity(String tripId, String sourceType, String sourceId, String reason) {
        Locked k = lockTrip(tripId);
        Map<String, Object> t = k.trip();
        if (sourceId == null || !sourceId.equals(t.get("weighSourceId")) || !java.util.Objects.equals(sourceType,
                t.get("weighSourceType")) || !TripStatusPolicy.isOpen((String) t.get("status"))) {
            return false;
        }
        for (Map<String, Object> l : lines(tripId)) {
            db.update(FleetLoadLine.class, (String) l.get("id"), Obj.of("loadedQty", null));
        }
        Map<String, Object> data = Obj.of("status", "loading", "weighedAt", null, "loadedTonnes", null,
                "weighSourceType", null, "weighSourceId", null, "weighedNetQty", null, "weighedUom", null,
                "updatedBy", TenantContext.userId());
        if (t.get("loadingStartedAt") == null) {
            data.put("loadingStartedAt", Instant.now());
        }
        db.update(FleetLoad.class, tripId, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, tripId, "measure", Obj.of("status", t.get("status"),
                "weighSourceId", sourceId), Obj.of("status", "loading", "reason", reason)));
        return true;
    }

    /** Manual loaded quantity per line (no weighbridge ticket). */
    @Transactional
    public Map<String, Object> recordLoadedQuantity(String tripId, List<LoadedLineInput> in) {
        Locked k = lockTrip(tripId);
        Map<String, Object> t = k.trip();
        assertWeighable(t);
        weighRules.assertManualLoadAllowed(t);
        List<Map<String, Object>> lines = lines(tripId);
        Map<String, BigDecimal> qty = new LinkedHashMap<>();
        Map<String, String> batches = new HashMap<>();
        for (LoadedLineInput li : in) {
            if (lines.stream().noneMatch(l -> li.loadLineId().equals(l.get("id")))) {
                throw ApiException.badRequest("Trip line not found");
            }
            qty.put(li.loadLineId(), li.quantity());
            if (FleetVehicleService.notBlank(li.batchNumber())) {
                batches.put(li.loadLineId(), li.batchNumber().trim());
            }
        }
        applyLoaded(t, lines, qty, batches);
        db.update(FleetLoad.class, tripId, Obj.of("weighSourceType", "manual", "weighSourceId", null,
                "weighedNetQty", null, "weighedUom", null));
        return get(tripId);
    }

    private void assertWeighable(Map<String, Object> t) {
        if (!TripStatusPolicy.isOpen((String) t.get("status"))) {
            throw ApiException.badRequest("Trip " + t.get("code") + " is " + TripStatusPolicy.label((String)
                    t.get("status")) + "; its loaded quantity can no longer change");
        }
    }

    private void applyLoaded(Map<String, Object> t, List<Map<String, Object>> lines, Map<String, BigDecimal> qty,
            Map<String, String> batches) {
        BigDecimal tonnes = BigDecimal.ZERO;
        boolean known = false;
        for (Map<String, Object> l : lines) {
            String lid = (String) l.get("id");
            BigDecimal q = qty.containsKey(lid) ? qty.get(lid)
                    : l.get("loadedQty") != null ? SqlRows.dec(l, "loadedQty") : SqlRows.dec(l, "plannedQty");
            Map<String, Object> data = Obj.of("loadedQty", q);
            if (batches.containsKey(lid)) {
                data.put("batchNumber", batches.get(lid));
            }
            db.update(FleetLoadLine.class, lid, data);
            if (l.get("tonnesPerUnit") != null) {
                tonnes = tonnes.add(q.multiply(SqlRows.dec(l, "tonnesPerUnit")));
                known = true;
            }
        }
        String status = (String) t.get("status");
        TripStatusPolicy.assertMove((String) t.get("code"), status, "weighed");
        db.update(FleetLoad.class, (String) t.get("id"), Obj.of("status", "weighed", "weighedAt", Instant.now(),
                "loadedTonnes", known ? tonnes.setScale(3, RoundingMode.HALF_UP) : null, "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) t.get("id"), "measure", Obj.of("status", status),
                Obj.of("status", "weighed", "lines", qty)));
    }

    /**
     * Dispatch (money / stock route): issue the goods from the yard exactly once and print the delivery note number.
     * Allowed from planned / loading / weighed; the loaded quantity defaults to the planned one.
     */
    @Transactional
    public Map<String, Object> dispatch(String id, BigDecimal odometer) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        Map<String, Object> order = k.order();
        String code = (String) t.get("code");
        TripStatusPolicy.assertMove(code, (String) t.get("status"), "dispatched");
        if ("sales_order".equals(order.get("sourceType"))) {
            DeliverySourcePort port = orders.sourcePort("sales_order", (String) order.get("sourceId"));
            if (port != null) {
                port.assertDeliverable((String) order.get("sourceId"));
            }
        }
        creditGate(order, t);
        if (t.get("stockIssuedAt") != null) {
            throw ApiException.conflict("Trip " + code + " already issued its stock");
        }
        assertAssigned(t);
        weighRules.assertDispatchable(t);
        recheckOwn(t);
        Instant now = Instant.now();
        boolean moveStock = "dispatch".equals(order.get("stockMode"));
        BigDecimal tonnes = BigDecimal.ZERO;
        boolean knownT = false;
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : lines(id)) {
            BigDecimal qty = l.get("loadedQty") != null ? SqlRows.dec(l, "loadedQty") : SqlRows.dec(l, "plannedQty");
            Map<String, Object> data = Obj.of("dispatchedQty", qty, "billableQty", qty, "loadedQty", qty);
            if (qty.signum() > 0 && moveStock && tracked((String) l.get("productId"))) {
                String batch = (String) l.get("batchNumber");
                StockMove m = StockMove.of((String) l.get("productId"), (String) t.get("locationId"), qty)
                        .uom((String) l.get("uomId"), SqlRows.dec(l, "uomFactor")).batch(batch)
                        .moveType("issue").source(STOCK_SOURCE, id).reference(code).date(now)
                        .notes("Delivery " + order.get("code") + " · trip " + code);
                if (batch != null) {
                    m.strategy("MANUAL");
                }
                StockMove.Issued issued = stock.issue(m);
                data.put("stockLedgerCode", issued.ledgerCode());
                data.put("unitCost", issued.unitCost());
                data.put("stockValue", issued.totalValue());
                total = total.add(issued.totalValue());
            }
            db.update(FleetLoadLine.class, (String) l.get("id"), data);
            BigDecimal planned = SqlRows.dec(l, "plannedQty");
            adjustOrderLine((String) l.get("deliveryOrderLineId"), qty.subtract(planned), qty, null, null);
            if (l.get("tonnesPerUnit") != null) {
                tonnes = tonnes.add(qty.multiply(SqlRows.dec(l, "tonnesPerUnit")));
                knownT = true;
            }
        }
        Map<String, Object> data = Obj.of("status", "dispatched", "dispatchedAt", now, "dispatchedBy",
                TenantContext.userId(), "stockIssuedAt", now, "loadedTonnes", knownT
                        ? tonnes.setScale(3, RoundingMode.HALF_UP) : t.get("loadedTonnes"),
                "deliveryNoteNumber", t.get("deliveryNoteNumber") != null ? t.get("deliveryNoteNumber")
                        : sequence.next("fleet_delivery_note", "DN-", 6),
                "updatedBy", TenantContext.userId());
        data.putAll(startMeter(t, odometer));
        Map<String, Object> after = db.update(FleetLoad.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "issue", Obj.of("status", t.get("status")),
                Obj.of("status", "dispatched", "stockValue", JsNumbers.dec(total), "deliveryNoteNumber",
                        after.get("deliveryNoteNumber"))));
        orders.refreshStatus((String) order.get("id"));
        events.publish(DomainEvents.FLEET_TRIP_DISPATCHED, tripEvent(after));
        return get(id);
    }

    /** dispatched → in transit (left the yard gate). */
    @Transactional
    public Map<String, Object> depart(String id) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        TripStatusPolicy.assertMove((String) t.get("code"), (String) t.get("status"), "in_transit");
        db.update(FleetLoad.class, id, Obj.of("status", "in_transit", "departedAt", Instant.now(), "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", t.get("status")),
                Obj.of("status", "in_transit")));
        return get(id);
    }

    /**
     * Proof of delivery (driver's phone): received and returned quantity per line, receiver, time, GPS (device, else the
     * vehicle's last telematics fix), signature image. Returned goods go back to stock at once; every difference raises a
     * discrepancy for approval. The trip becomes {@code delivered}.
     */
    @Transactional
    public Map<String, Object> proofOfDelivery(String id, PodInput in) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        Map<String, Object> order = k.order();
        String code = (String) t.get("code");
        TripStatusPolicy.assertMove(code, (String) t.get("status"), "delivered");
        Instant receivedAt = in.receivedAt() == null ? Instant.now() : in.receivedAt();
        if (receivedAt.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("The delivery time cannot be in the future");
        }
        if (t.get("dispatchedAt") != null && receivedAt.isBefore((Instant) t.get("dispatchedAt"))) {
            throw ApiException.badRequest("The delivery time is before the dispatch time");
        }
        if ((in.latitude() == null) != (in.longitude() == null)) {
            throw ApiException.badRequest("Give both latitude and longitude, or neither");
        }
        List<Map<String, Object>> lines = lines(id);
        Map<String, PodLineInput> byLine = new HashMap<>();
        for (PodLineInput p : in.lines()) {
            if (lines.stream().noneMatch(l -> p.loadLineId().equals(l.get("id")))) {
                throw ApiException.badRequest("Trip line not found");
            }
            byLine.put(p.loadLineId(), p);
        }
        record Diff(Map<String, Object> line, String kind, String reason, BigDecimal dispatched, BigDecimal delivered,
                BigDecimal diff, String notes) {}
        List<Diff> diffs = new ArrayList<>();
        BigDecimal deliveredT = BigDecimal.ZERO;
        boolean knownT = false;
        for (Map<String, Object> l : lines) {
            BigDecimal dispatched = SqlRows.dec(l, "dispatchedQty");
            PodLineInput p = byLine.get(l.get("id"));
            if (p == null) {
                if (dispatched.signum() > 0) {
                    throw ApiException.badRequest("Enter the delivered quantity of every product on the trip");
                }
                continue;
            }
            BigDecimal delivered = p.deliveredQty();
            BigDecimal returned = p.returnedQty() == null ? BigDecimal.ZERO : p.returnedQty();
            if (returned.compareTo(dispatched) > 0) {
                throw ApiException.badRequest("More is returned than was dispatched on trip " + code);
            }
            BigDecimal shortQty = dispatched.subtract(delivered).subtract(returned);
            String productName = productName((String) l.get("productId"));
            if ((shortQty.signum() != 0 || returned.signum() > 0) && p.reason() == null) {
                throw ApiException.badRequest("A reason is required for the difference on " + productName);
            }
            Map<String, Object> data = Obj.of("deliveredQty", delivered, "returnedQty", returned);
            if (returned.signum() > 0) {
                String ledger = putBack(t, order, l, returned, "Returned at delivery of trip " + code);
                data.put("returnLedgerCode", ledger);
                diffs.add(new Diff(l, "returned", p.reason(), dispatched, delivered, returned, p.notes()));
            }
            if (shortQty.signum() != 0) {
                diffs.add(new Diff(l, shortQty.signum() > 0 ? "shortage" : "excess", p.reason(), dispatched, delivered,
                        shortQty, p.notes()));
            }
            db.update(FleetLoadLine.class, (String) l.get("id"), data);
            adjustOrderLine((String) l.get("deliveryOrderLineId"), returned.negate(), null, delivered, returned);
            if (l.get("tonnesPerUnit") != null) {
                deliveredT = deliveredT.add(delivered.multiply(SqlRows.dec(l, "tonnesPerUnit")));
                knownT = true;
            }
        }
        BigDecimal lat = in.latitude();
        BigDecimal lon = in.longitude();
        String locSource = lat != null ? "device" : "none";
        if (lat == null && t.get("vehicleId") != null) {
            var fix = telematics.lastKnownPosition((String) t.get("vehicleId"));
            if (fix.isPresent() && Duration.between(fix.get().at(), receivedAt).abs().compareTo(Duration.ofHours(6)) < 0) {
                lat = fix.get().latitude();
                lon = fix.get().longitude();
                locSource = "telematics";
            }
        }
        String signatureFileId = null;
        if (FleetVehicleService.notBlank(in.signature())) {
            Matcher m = DATA_URL.matcher(in.signature().trim());
            if (!m.matches()) {
                throw ApiException.badRequest("The signature must be a PNG or JPEG image (data URL)");
            }
            byte[] bytes;
            try {
                bytes = Base64.getMimeDecoder().decode(m.group(2));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("The signature must be a PNG or JPEG image (data URL)");
            }
            String ext = "png".equals(m.group(1)) ? "png" : "jpg";
            signatureFileId = (String) files.upload("signature-" + code + "." + ext, "image/" + m.group(1), bytes,
                    OWNER_TYPE, id).get("id");
        }
        db.insert(FleetProofOfDelivery.class, Obj.of("loadId", id, "receiverName", in.receiverName().trim(),
                "receiverPhone", FleetVehicleService.trim(in.receiverPhone()), "receivedAt", receivedAt, "latitude", lat,
                "longitude", lon, "locationSource", locSource, "signatureFileId", signatureFileId, "notes",
                FleetVehicleService.trim(in.notes()), "recordedBy", TenantContext.userId()));
        Map<String, Object> data = Obj.of("status", "delivered", "deliveredAt", receivedAt, "deliveredTonnes",
                knownT ? deliveredT.setScale(3, RoundingMode.HALF_UP) : null, "updatedBy", TenantContext.userId());
        if (in.endOdometer() != null) {
            data.putAll(endMeter(t, in.endOdometer(), receivedAt));
        }
        Map<String, Object> after = db.update(FleetLoad.class, id, data);
        for (Diff d : diffs) {
            discrepancies.raise(after, d.line(), d.kind(), d.reason(), d.dispatched(), d.delivered(), d.diff(),
                    d.notes());
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", t.get("status")),
                Obj.of("status", "delivered", "receiver", in.receiverName().trim(), "discrepancies", diffs.size())));
        orders.refreshStatus((String) order.get("id"));
        events.publish(DomainEvents.FLEET_TRIP_DELIVERED, tripEvent(after));
        return get(id);
    }

    /** Photo of the delivered goods / signed paper note (after the POD). */
    @Transactional
    public Map<String, Object> podPhoto(String id, String filename, String contentType, byte[] bytes) {
        Map<String, Object> t = require(id, true);
        if (contentType == null || !contentType.startsWith("image/")) {
            throw ApiException.badRequest("Photos must be image files");
        }
        Map<String, Object> pod = SqlRows.one(FleetProofOfDelivery.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetProofOfDelivery\" WHERE \"organizationId\" = ? AND \"loadId\" = ? FOR UPDATE")
                .params(FleetDb.org(), id));
        if (pod == null) {
            throw ApiException.badRequest("Record the proof of delivery of trip " + t.get("code") + " first");
        }
        String old = (String) pod.get("photoFileId");
        Map<String, Object> file = files.upload(filename, contentType, bytes, OWNER_TYPE, id);
        db.update(FleetProofOfDelivery.class, (String) pod.get("id"), Obj.of("photoFileId", file.get("id")));
        if (old != null) {
            files.remove(old);
        }
        return get(id);
    }

    /** delivered → closed: end odometer, distance (manual, odometer, telematics, or site distance) and freight. */
    @Transactional
    public Map<String, Object> close(String id, CloseTripInput in) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        Map<String, Object> order = k.order();
        String code = (String) t.get("code");
        TripStatusPolicy.assertMove(code, (String) t.get("status"), "closed");
        Map<String, Object> data = Obj.of("status", "closed", "closedAt", Instant.now(), "updatedBy",
                TenantContext.userId());
        BigDecimal end = in == null ? null : in.endOdometer();
        if (end != null && t.get("endOdometer") == null) {
            data.putAll(endMeter(t, end, Instant.now()));
        }
        BigDecimal startOdo = (BigDecimal) t.get("startOdometer");
        BigDecimal endOdo = data.get("endOdometer") != null ? (BigDecimal) data.get("endOdometer")
                : (BigDecimal) t.get("endOdometer");
        BigDecimal km = null;
        String source = null;
        if (in != null && in.distanceKm() != null) {
            km = in.distanceKm();
            source = "manual";
        } else if (startOdo != null && endOdo != null && endOdo.compareTo(startOdo) >= 0) {
            km = endOdo.subtract(startOdo);
            source = "odometer";
        } else if (t.get("vehicleId") != null && t.get("dispatchedAt") != null) {
            Instant to = t.get("deliveredAt") != null ? (Instant) t.get("deliveredAt") : Instant.now();
            BigDecimal gps = telematics.distanceKm((String) t.get("vehicleId"), (Instant) t.get("dispatchedAt"),
                    to.plus(Duration.ofHours(12)));
            if (gps != null && gps.signum() > 0) {
                km = gps;
                source = "telematics";
            }
        }
        if (km == null && order.get("siteId") != null) {
            BigDecimal siteKm = db.jdbc().sql("SELECT \"distanceKm\" FROM \"FleetCustomerSite\" WHERE id = ?")
                    .param(order.get("siteId")).query(BigDecimal.class).optional().orElse(null);
            if (siteKm != null) {
                km = siteKm;
                source = "site";
            }
        }
        if (km != null) {
            data.put("distanceKm", km.setScale(1, RoundingMode.HALF_UP));
            data.put("distanceSource", source);
        }
        if ("transporter".equals(t.get("carrier"))) {
            BigDecimal tonnes = t.get("deliveredTonnes") != null ? (BigDecimal) t.get("deliveredTonnes")
                    : t.get("loadedTonnes") != null ? (BigDecimal) t.get("loadedTonnes")
                    : (BigDecimal) t.get("plannedTonnes");
            data.put("freightAmount", FreightCalculator.amount((String) t.get("freightBasis"),
                    (BigDecimal) t.get("freightRate"), tonnes, km));
        }
        Map<String, Object> after = db.update(FleetLoad.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", t.get("status")), after));
        orders.refreshStatus((String) order.get("id"));
        Map<String, Object> ev = tripEvent(after);
        ev.put("distanceKm", after.get("distanceKm") == null ? null : JsNumbers.dec((BigDecimal) after.get("distanceKm")));
        ev.put("freightAmount", after.get("freightAmount") == null ? null
                : JsNumbers.dec((BigDecimal) after.get("freightAmount")));
        events.publish(DomainEvents.FLEET_TRIP_CLOSED, ev);
        return get(id);
    }

    /** Cancel before the goods leave; the planned quantity becomes plannable again. */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        TripStatusPolicy.assertMove((String) t.get("code"), (String) t.get("status"), "cancelled");
        for (Map<String, Object> l : lines(id)) {
            adjustOrderLine((String) l.get("deliveryOrderLineId"), SqlRows.dec(l, "plannedQty").negate(), null, null,
                    null);
        }
        db.update(FleetLoad.class, id, Obj.of("status", "cancelled", "cancelledAt", Instant.now(), "cancelReason",
                reason.trim(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "cancel", Obj.of("status", t.get("status")),
                Obj.of("status", "cancelled", "reason", reason.trim())));
        orders.refreshStatus((String) k.order().get("id"));
        return get(id);
    }

    /**
     * The whole load came back (customer refused it, site closed…): every line is received back into the yard at its
     * issue cost, the quantity is plannable again and a {@code returned} discrepancy per line goes for approval.
     */
    @Transactional
    public Map<String, Object> returnLoad(String id, ReturnInput in) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        Map<String, Object> order = k.order();
        String code = (String) t.get("code");
        TripStatusPolicy.assertMove(code, (String) t.get("status"), "returned");
        Instant now = Instant.now();
        Map<String, Object> after = null;
        List<Map<String, Object>> lines = lines(id);
        for (Map<String, Object> l : lines) {
            BigDecimal qty = SqlRows.dec(l, "dispatchedQty");
            Map<String, Object> data = Obj.of("deliveredQty", BigDecimal.ZERO, "returnedQty", qty);
            if (qty.signum() > 0) {
                data.put("returnLedgerCode", putBack(t, order, l, qty, "Load returned: trip " + code));
            }
            db.update(FleetLoadLine.class, (String) l.get("id"), data);
            adjustOrderLine((String) l.get("deliveryOrderLineId"), qty.negate(), null, null, qty);
        }
        after = db.update(FleetLoad.class, id, Obj.of("status", "returned", "returnedAt", now, "returnReason",
                in.reason().trim(), "stockReturnedAt", now, "updatedBy", TenantContext.userId()));
        String reason = in.reasonCode() == null ? "customer_rejected" : in.reasonCode();
        for (Map<String, Object> l : lines) {
            BigDecimal qty = SqlRows.dec(l, "dispatchedQty");
            if (qty.signum() > 0) {
                discrepancies.raise(after, l, "returned", reason, qty, BigDecimal.ZERO, qty, in.reason().trim());
            }
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "receive", Obj.of("status", t.get("status")),
                Obj.of("status", "returned", "reason", in.reason().trim())));
        orders.refreshStatus((String) order.get("id"));
        Map<String, Object> ev = tripEvent(after);
        ev.put("reason", in.reason().trim());
        events.publish(DomainEvents.FLEET_TRIP_RETURNED, ev);
        return get(id);
    }

    /** Count a delivery-note print (the PDF says COPY from the second print on). Returns the print number. */
    @Transactional
    public int countDeliveryNotePrint(String id) {
        Map<String, Object> t = require(id, true);
        if (t.get("deliveryNoteNumber") == null) {
            throw ApiException.badRequest("The delivery note of trip " + t.get("code") + " is printed once it is"
                    + " dispatched");
        }
        int n = ((Number) t.get("deliveryNotePrints")).intValue() + 1;
        db.update(FleetLoad.class, id, Obj.of("deliveryNotePrints", n));
        return n;
    }

    // ------------------------------------------------------------------ helpers

    private record Locked(Map<String, Object> trip, Map<String, Object> order) {}

    /**
     * Credit check point {@code trip_dispatch} (ADR-S011 S3): a customer blocked by credit (hard hold) cannot be
     * delivered; a credit hold (limit / overdue) needs a {@code credit:override} holder, whose dispatch records the
     * override.
     */
    private void creditGate(Map<String, Object> order, Map<String, Object> trip) {
        String partnerId = (String) order.get("partnerId");
        CreditService.Decision c = credit.check("trip_dispatch", partnerId, "fleet_trip", (String) trip.get("id"),
                (String) trip.get("code"), BigDecimal.ZERO, null);
        if (c.passed()) {
            return;
        }
        if ("blocked".equals(c.result()) || !TenantContext.has(Permissions.CREDIT__OVERRIDE)) {
            throw ApiException.badRequest("Credit check failed for this customer: " + String.join("; ", c.reasons())
                    + ("blocked".equals(c.result()) ? "" : " — a credit override is needed to dispatch"));
        }
        credit.override(partnerId, "fleet_trip", (String) trip.get("id"), (String) trip.get("code"),
                "Dispatched with credit override");
    }

    /** Lock order: delivery order, then the trip. */
    private Locked lockTrip(String id) {
        Map<String, Object> t = require(id, false);
        Map<String, Object> order = orders.require((String) t.get("deliveryOrderId"), true);
        return new Locked(require(id, true), order);
    }

    private List<Map<String, Object>> lines(String tripId) {
        return db.list(FleetLoadLine.class, " AND \"loadId\" = ? ORDER BY \"createdAt\", id", tripId);
    }

    /** Receive returned goods back into the trip's yard at the issue cost (current cost in stock mode invoiced). */
    private String putBack(Map<String, Object> t, Map<String, Object> order, Map<String, Object> l, BigDecimal qty,
            String notes) {
        if (!tracked((String) l.get("productId"))) {
            return null;
        }
        boolean issuedHere = l.get("stockLedgerCode") != null;
        if ("dispatch".equals(order.get("stockMode")) && !issuedHere) {
            return null;
        }
        StockMove m = StockMove.of((String) l.get("productId"), (String) t.get("locationId"), qty)
                .uom((String) l.get("uomId"), SqlRows.dec(l, "uomFactor")).batch((String) l.get("batchNumber"))
                .source(STOCK_SOURCE + "_return", (String) t.get("id")).reference((String) t.get("code")).notes(notes);
        if (issuedHere && l.get("unitCost") != null) {
            m.unitCost(SqlRows.dec(l, "unitCost"));
        }
        return stock.receiveReturn(m).ledgerCode();
    }

    private void assertAssigned(Map<String, Object> t) {
        if ("own".equals(t.get("carrier"))) {
            if (t.get("vehicleId") == null || t.get("driverId") == null) {
                throw ApiException.badRequest("Assign a truck and a driver to trip " + t.get("code") + " first");
            }
        } else if (t.get("transporterPartnerId") == null) {
            throw ApiException.badRequest("Assign a transporter to trip " + t.get("code") + " first");
        }
    }

    /** Own fleet: the truck (and trailer) must still be dispatchable and the driver eligible today. */
    private void recheckOwn(Map<String, Object> t) {
        if (!"own".equals(t.get("carrier"))) {
            return;
        }
        String vehicleId = (String) t.get("vehicleId");
        vehicles.require(vehicleId, true);
        eligibility.assertEligible(vehicleId);
        if (t.get("trailerId") != null) {
            eligibility.assertEligible((String) t.get("trailerId"));
        }
        Map<String, Object> d = drivers.require((String) t.get("driverId"), true);
        DriverEligibilityPolicy.assertEligible(drivers.facts(d), drivers.vehicleFacts(vehicleId, false),
                FleetDeliveryOrderService.today(), "dispatched");
    }

    private Map<String, Object> startMeter(Map<String, Object> t, BigDecimal odometer) {
        if (odometer == null || t.get("startReadingId") != null || t.get("vehicleId") == null) {
            return Map.of();
        }
        Map<String, Object> r = meters.recordAutomated((String) t.get("vehicleId"), "odometer", odometer, Instant.now(),
                "trip", t.get("id") + ":start", "Trip " + t.get("code") + " start");
        return Obj.of("startOdometer", odometer, "startReadingId", r.get("id"));
    }

    private Map<String, Object> endMeter(Map<String, Object> t, BigDecimal odometer, Instant at) {
        if (t.get("vehicleId") == null) {
            return Map.of();
        }
        Instant when = at.isAfter(Instant.now()) ? Instant.now() : at;
        Map<String, Object> r = meters.recordAutomated((String) t.get("vehicleId"), "odometer", odometer, when, "trip",
                t.get("id") + ":end", "Trip " + t.get("code") + " end");
        return Obj.of("endOdometer", odometer, "endReadingId", r.get("id"));
    }

    private boolean tracked(String productId) {
        return Boolean.TRUE.equals(db.jdbc().sql("SELECT \"trackInventory\" FROM \"Product\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(productId, FleetDb.org()).query(Boolean.class).optional()
                .orElse(false));
    }

    private String productName(String productId) {
        return db.jdbc().sql("SELECT name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, FleetDb.org()).query(String.class).optional().orElse("");
    }

    static Map<String, Object> tripEvent(Map<String, Object> t) {
        return Obj.of("organizationId", FleetDb.org(), "loadId", t.get("id"), "code", t.get("code"), "deliveryOrderId",
                t.get("deliveryOrderId"), "vehicleId", t.get("vehicleId"), "driverId", t.get("driverId"), "carrier",
                t.get("carrier"));
    }

    private static String unit(Map<String, Object> l) {
        return l.get("uomCode") == null ? "" : (String) l.get("uomCode");
    }

    private static void eq(StringBuilder where, List<Object> params, String col, String v) {
        if (FleetVehicleService.notBlank(v)) {
            where.append(" AND ").append(col).append(" = ?");
            params.add(v);
        }
    }

    private static BigDecimal sum(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a.add(b);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
