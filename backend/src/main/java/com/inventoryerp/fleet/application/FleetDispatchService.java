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
import com.inventoryerp.invoicing.application.CreditControlService;
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
    /** Phase A A2: customer weight evidence (set after construction; keeps the constructor stable). */
    private CustomerWeightEvidence customerWeights;
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
    private final CreditControlService creditControl;

    private final CreditService credit;

    private com.inventoryerp.core.application.SaleableProducts saleable;
    private org.springframework.beans.factory.ObjectProvider<DispatchGate> dispatchGates;

    /** R4d (DEL-4): source modules' dispatch checks, run by every dispatch route (resolved lazily: no bean cycle). */
    @org.springframework.beans.factory.annotation.Autowired
    void setDispatchGates(org.springframework.beans.factory.ObjectProvider<DispatchGate> dispatchGates) {
        this.dispatchGates = dispatchGates;
    }

    /** Sell at any stage (ADR-S060): only the product's own switch stops a sale. */
    @org.springframework.beans.factory.annotation.Autowired
    void setSaleable(com.inventoryerp.core.application.SaleableProducts saleable) {
        this.saleable = saleable;
    }

    public FleetDispatchService(FleetDb db, FleetDeliveryOrderService orders, FleetDiscrepancyService discrepancies,
            FleetVehicleService vehicles, FleetDriverService drivers, FleetDispatchEligibilityService eligibility,
            FleetMeterService meters, FleetTelematicsService telematics, FleetLoadFactors factors, StockService stock,
            PosStockLocation stockLocations, FilesService files, SequenceService sequence, AuditService audit,
            EventBus events, FleetWeighbridgeRuleService weighRules, CreditService credit,
            CreditControlService creditControl) {
        this.creditControl = creditControl;
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

    @org.springframework.beans.factory.annotation.Autowired
    void setCustomerWeights(CustomerWeightEvidence customerWeights) {
        this.customerWeights = customerWeights;
    }

    private LoadLineQuantityService quantities;
    private LoadLineHistory history;
    private com.inventoryerp.core.application.UomConversionService uoms;

    @org.springframework.beans.factory.annotation.Autowired
    void setQuantities(LoadLineQuantityService quantities, LoadLineHistory history,
            com.inventoryerp.core.application.UomConversionService uoms) {
        this.quantities = quantities;
        this.history = history;
        this.uoms = uoms;
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
            List<String> evidence = new ArrayList<>();
            if (pod.get("customerWeightFileIds") instanceof List<?> ids) {
                ids.forEach(f -> evidence.add(url((String) f)));
            }
            pod.put("customerWeightFileUrls", evidence);
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

    /**
     * Re-plan the quantities of a trip that has not started loading: {@code in} is the full new set (a delivery-order
     * line left out leaves the trip). Each line's increase must fit what remains to be planned, under the same delivery
     * order lock as {@link #addLoad}, so two edits can never plan more than was ordered.
     */
    @Transactional
    public Map<String, Object> replan(String id, List<LoadLineInput> in) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        String code = (String) t.get("code");
        if (!"planned".equals(t.get("status"))) {
            throw ApiException.badRequest("Trip " + code + " is " + TripStatusPolicy.label((String) t.get("status"))
                    + "; its quantities can no longer change");
        }
        String deliveryOrderId = (String) k.order().get("id");
        FleetDeliveryOrderService.assertEditable(k.order());
        Map<String, Map<String, Object>> doLines = new HashMap<>();
        for (Map<String, Object> l : orders.lines(deliveryOrderId)) {
            doLines.put((String) l.get("id"), l);
        }
        Map<String, BigDecimal> wanted = new LinkedHashMap<>();
        Map<String, String> batches = new HashMap<>();
        for (LoadLineInput li : in) {
            if (!doLines.containsKey(li.deliveryOrderLineId())) {
                throw ApiException.badRequest("Delivery order line not found");
            }
            if (li.quantity() == null || li.quantity().signum() < 0) {
                throw ApiException.badRequest("Quantities must not be less than 0");
            }
            wanted.merge(li.deliveryOrderLineId(), li.quantity(), BigDecimal::add);
            if (li.batchNumber() != null) {
                batches.put(li.deliveryOrderLineId(), li.batchNumber());
            }
        }
        wanted.values().removeIf(q -> q.signum() == 0);
        if (wanted.isEmpty()) {
            throw ApiException.badRequest("Trip " + code + " needs at least one product; cancel it instead");
        }
        Map<String, Map<String, Object>> current = new HashMap<>();
        for (Map<String, Object> l : lines(id)) {
            current.put((String) l.get("deliveryOrderLineId"), l);
        }
        for (Map.Entry<String, BigDecimal> e : wanted.entrySet()) {
            Map<String, Object> dl = doLines.get(e.getKey());
            Map<String, Object> cur = current.get(e.getKey());
            BigDecimal mine = cur == null ? BigDecimal.ZERO : SqlRows.dec(cur, "plannedQty");
            BigDecimal remaining = SqlRows.dec(dl, "remainingQty");
            if (e.getValue().subtract(mine).compareTo(remaining) > 0) {
                throw ApiException.badRequest("Only " + JsNumbers.dec(remaining.add(mine)) + " " + unit(dl) + " of "
                        + dl.get("productName") + " remain to be planned");
            }
        }
        Map<String, Object> before = Obj.of("lines", current.values().stream().map(l -> Obj.of("deliveryOrderLineId",
                l.get("deliveryOrderLineId"), "plannedQty", l.get("plannedQty"))).toList());
        BigDecimal tonnes = BigDecimal.ZERO;
        BigDecimal m3 = BigDecimal.ZERO;
        boolean knownT = false;
        boolean knownM = false;
        for (Map<String, Object> cur : current.values()) {
            String doLine = (String) cur.get("deliveryOrderLineId");
            if (!wanted.containsKey(doLine)) {
                db.jdbc().sql("DELETE FROM \"FleetLoadLine\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(cur.get("id"), FleetDb.org()).update();
                adjustOrderLine(doLine, SqlRows.dec(cur, "plannedQty").negate(), null, null, null);
            }
        }
        for (Map.Entry<String, BigDecimal> e : wanted.entrySet()) {
            Map<String, Object> dl = doLines.get(e.getKey());
            Map<String, Object> cur = current.get(e.getKey());
            String batch = FleetVehicleService.trim(batches.getOrDefault(e.getKey(), cur == null
                    ? (String) dl.get("batchNumber") : (String) cur.get("batchNumber")));
            if (cur == null) {
                db.insert(FleetLoadLine.class, Obj.of("loadId", id, "deliveryOrderLineId", e.getKey(), "productId",
                        dl.get("productId"), "uomId", dl.get("uomId"), "uomFactor", dl.get("uomFactor"), "plannedQty",
                        e.getValue(), "tonnesPerUnit", dl.get("tonnesPerUnit"), "m3PerUnit", dl.get("m3PerUnit"),
                        "batchNumber", batch));
                adjustOrderLine(e.getKey(), e.getValue(), null, null, null);
            } else {
                db.update(FleetLoadLine.class, (String) cur.get("id"), Obj.of("plannedQty", e.getValue(), "batchNumber",
                        batch));
                adjustOrderLine(e.getKey(), e.getValue().subtract(SqlRows.dec(cur, "plannedQty")), null, null, null);
            }
            if (dl.get("tonnesPerUnit") != null) {
                tonnes = tonnes.add(e.getValue().multiply((BigDecimal) dl.get("tonnesPerUnit")));
                knownT = true;
            }
            if (dl.get("m3PerUnit") != null) {
                m3 = m3.add(e.getValue().multiply((BigDecimal) dl.get("m3PerUnit")));
                knownM = true;
            }
        }
        db.update(FleetLoad.class, id, Obj.of("plannedTonnes", knownT ? tonnes.setScale(3, RoundingMode.HALF_UP) : null,
                "plannedM3", knownM ? m3.setScale(3, RoundingMode.HALF_UP) : null, "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, Obj.of("lines", wanted.entrySet().stream()
                .map(e -> Obj.of("deliveryOrderLineId", e.getKey(), "plannedQty", e.getValue())).toList())));
        orders.refreshStatus(deliveryOrderId);
        return get(id);
    }

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
    /**
     * Phase I2 M-6: the base-unit quantity this load issues when its line is in another unit, rounded cumulatively per
     * delivery-order line — round(Σ issued before + this load) − round(Σ issued before) — so two loads of 10.37 t of
     * 50-kg bags issue 415 bags, not 2 × 207. Null (let the engine convert) for base-unit lines or when the result
     * would not be positive.
     */
    private BigDecimal cumulativeBase(Map<String, Object> l, BigDecimal qty) {
        if (l.get("uomFactor") == null || l.get("deliveryOrderLineId") == null) {
            return null;
        }
        BigDecimal factor = SqlRows.dec(l, "uomFactor");
        if (factor.signum() <= 0 || factor.compareTo(BigDecimal.ONE) == 0) {
            return null;
        }
        String baseUom = db.jdbc().sql("SELECT \"uomId\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?").params(l.get("productId"), FleetDb.org())
                .query(String.class).optional().orElse(null);
        if (baseUom == null) {
            return null;
        }
        BigDecimal before = db.jdbc().sql("SELECT COALESCE(sum(ll.\"dispatchedQty\"), 0) FROM \"FleetLoadLine\" ll JOIN"
                + " \"FleetLoad\" t ON t.id = ll.\"loadId\" WHERE ll.\"organizationId\" = ? AND"
                + " ll.\"deliveryOrderLineId\" = ? AND ll.id <> ? AND t.\"stockIssuedAt\" IS NOT NULL AND"
                + " ll.\"uomFactor\" = ?").params(FleetDb.org(), l.get("deliveryOrderLineId"), l.get("id"), factor)
                .query(BigDecimal.class).single();
        BigDecimal base = uoms.applyFactor(before.add(qty), factor, baseUom, true)
                .subtract(uoms.applyFactor(before, factor, baseUom, true));
        return base.signum() > 0 ? base : null;
    }

    /** One evidence file of a POD correction (photo or PDF). */
    public record Evidence(String filename, String contentType, byte[] bytes) {}

    /** One corrected line: the new delivered quantity Δ'. */
    public record PodCorrectionLine(String loadLineId, BigDecimal deliveredQty) {}

    /**
     * Phase A A3 (plan §2): authorised POD correction. Raises the delivered quantity of lines of a delivered trip (never
     * above D − R − G, never down), reconciles that much of the lines' open POD-shortage claims, and records the
     * change in the line history. Needs a reason and evidence; the person who recorded the POD cannot correct it.
     * Lock order (plan §8): the caller holds the sales order; here delivery order → trip → claims.
     */
    @Transactional
    public Map<String, Object> correctPod(String id, List<PodCorrectionLine> in, String reason, List<Evidence> evidence) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        String code = (String) t.get("code");
        if (!List.of("delivered", "closed").contains(t.get("status"))) {
            throw ApiException.badRequest("Trip " + code + " has no proof of delivery to correct");
        }
        String why = FleetVehicleService.trim(reason);
        if (why == null) {
            throw ApiException.badRequest("A reason is required to correct a proof of delivery");
        }
        if (evidence == null || evidence.isEmpty()) {
            throw ApiException.badRequest("Evidence is required to correct a proof of delivery");
        }
        if (in == null || in.isEmpty()) {
            throw ApiException.badRequest("lines should not be empty");
        }
        Map<String, Object> pod = SqlRows.one(db.jdbc().sql("SELECT \"recordedBy\" FROM \"FleetProofOfDelivery\" WHERE"
                + " \"loadId\" = ? AND \"organizationId\" = ?").params(id, FleetDb.org()));
        String actor = TenantContext.userId();
        if (pod != null && actor != null && actor.equals(pod.get("recordedBy"))) {
            throw ApiException.forbidden("The person who recorded the proof of delivery cannot correct it");
        }
        List<String> fileIds = new ArrayList<>();
        for (Evidence e : evidence) {
            if (e.contentType() == null || !(e.contentType().startsWith("image/")
                    || "application/pdf".equals(e.contentType()))) {
                throw ApiException.badRequest("Correction evidence must be an image or a PDF");
            }
            fileIds.add((String) files.upload(e.filename() == null ? "pod-correction-" + code : e.filename(),
                    e.contentType(), e.bytes(), OWNER_TYPE, id).get("id"));
        }
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> l : lines(id)) {
            byId.put((String) l.get("id"), l);
        }
        for (PodCorrectionLine c : in) {
            Map<String, Object> l = byId.get(c.loadLineId());
            if (l == null) {
                throw ApiException.badRequest("Trip line not found");
            }
            BigDecimal before = LoadLineQuantityService.dec(l, "deliveredQty");
            BigDecimal ceiling = LoadLineQuantityService.dec(l, "dispatchedQty")
                    .subtract(LoadLineQuantityService.dec(l, "returnedQty"))
                    .subtract(LoadLineQuantityService.dec(l, "damagedQty"));
            String name = productName((String) l.get("productId"));
            if (c.deliveredQty() == null || c.deliveredQty().compareTo(before) <= 0) {
                throw ApiException.badRequest("A proof-of-delivery correction can only raise the delivered quantity of "
                        + name + " (now " + JsNumbers.dec(before) + ")");
            }
            if (c.deliveredQty().compareTo(ceiling) > 0) {
                throw ApiException.badRequest("The corrected delivered quantity of " + name + " cannot exceed "
                        + JsNumbers.dec(ceiling) + " (dispatched less returned and damaged)");
            }
            BigDecimal x = c.deliveredQty().subtract(before);
            BigDecimal unmatched = discrepancies.reconcileShortage(l, x, why, fileIds);
            if (unmatched.signum() > 0) {
                throw ApiException.badRequest("The correction of " + name + " is more than its open shortage claims"
                        + " (" + JsNumbers.dec(x.subtract(unmatched)) + ")");
            }
            db.update(FleetLoadLine.class, (String) l.get("id"), Obj.of("deliveredQty", c.deliveredQty()));
            BigDecimal dispatchedQ = LoadLineQuantityService.dec(l, "dispatchedQty");
            BigDecimal damagedQ = LoadLineQuantityService.dec(l, "damagedQty");
            BigDecimal shortNow = dispatchedQ.subtract(c.deliveredQty()).subtract(LoadLineQuantityService.dec(l,
                    "returnedQty")).subtract(damagedQ).max(BigDecimal.ZERO);
            syncDeliveryLoss(k.order(), l, damagedQ.add(shortNow), dispatchedQ, id, code);
            adjustOrderLine((String) l.get("deliveryOrderLineId"), null, null, x, null);
            history.record(l, "pod_corrected", "delivered", before, c.deliveredQty(), why, fileIds, "trip", id);
            quantities.refresh((String) l.get("id"));
            audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("loadLineId", l.get("id"),
                    "deliveredQty", JsNumbers.dec(before)), Obj.of("loadLineId", l.get("id"), "deliveredQty",
                            JsNumbers.dec(c.deliveredQty()), "reason", why, "evidenceFileIds", fileIds)));
        }
        orders.refreshStatus((String) k.order().get("id"));
        return get(id);
    }

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
        data.putAll(startMeter(t, odometer, Instant.now()));
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
            String code = db.jdbc().sql("SELECT code FROM \"UnitOfMeasure\" WHERE id = ? AND \"organizationId\" = ?").params(l.get("uomId"), FleetDb.org())
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

    /**
     * Sales readiness R4d (DEL-1 / DEL-7): a sales-order trip never dispatches more of a line than the order allows.
     * What all trips of the delivery-order line then hold (its planned quantity, with this trip's loaded quantity in
     * place of its planned one) must stay within the line's ordered quantity plus the order line's tolerance.
     */
    private void assertWithinSource(DeliverySourcePort port, String tripId, String code) {
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT tl.\"plannedQty\", tl.\"loadedQty\","
                + " ol.\"orderedQty\", ol.\"plannedQty\" AS \"linePlanned\", ol.\"sourceLineId\", ol.description FROM"
                + " \"FleetLoadLine\" tl JOIN \"FleetDeliveryOrderLine\" ol ON ol.id = tl.\"deliveryOrderLineId\" WHERE"
                + " tl.\"organizationId\" = ? AND tl.\"loadId\" = ?").params(FleetDb.org(), tripId))) {
            BigDecimal planned = SqlRows.dec(l, "plannedQty");
            BigDecimal qty = l.get("loadedQty") != null ? SqlRows.dec(l, "loadedQty") : planned;
            BigDecimal after = SqlRows.dec(l, "linePlanned").subtract(planned).add(qty);
            BigDecimal tolerance = l.get("sourceLineId") == null ? BigDecimal.ZERO
                    : port.overDeliveryTolerancePct((String) l.get("sourceLineId"));
            BigDecimal allowed = SqlRows.dec(l, "orderedQty").multiply(BigDecimal.ONE.add(tolerance.movePointLeft(2)))
                    .setScale(3, RoundingMode.HALF_UP);
            if (after.compareTo(allowed) > 0) {
                throw ApiException.badRequest("Trip " + code + " would dispatch more of \"" + l.get("description")
                        + "\" than the order allows (" + allowed.stripTrailingZeros().toPlainString() + " in total on its"
                        + " deliveries); reduce the loaded quantity");
            }
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
        return dispatchAt(id, odometer, Instant.now());
    }

    /**
     * {@link #dispatch} on a given moment: the stock issue, its journal entry, the trip times and the start reading all
     * carry {@code at}. For the demo seed's past days; no route exposes it.
     */
    @Transactional
    public Map<String, Object> dispatchAt(String id, BigDecimal odometer, Instant at) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        Map<String, Object> order = k.order();
        String code = (String) t.get("code");
        TripStatusPolicy.assertMove(code, (String) t.get("status"), "dispatched");
        if ("sales_order".equals(order.get("sourceType"))) {
            DeliverySourcePort port = orders.sourcePort("sales_order", (String) order.get("sourceId"));
            if (port != null) {
                port.assertDeliverable((String) order.get("sourceId"));
                assertWithinSource(port, id, code);
            }
        }
        creditGate(order, t);
        if (t.get("stockIssuedAt") != null) {
            throw ApiException.conflict("Trip " + code + " already issued its stock");
        }
        if (dispatchGates != null) {
            dispatchGates.orderedStream().forEach(gate -> gate.beforeDispatch(id, (String) order.get("sourceType")));
        }
        assertAssigned(t);
        weighRules.assertDispatchable(t);
        recheckOwn(t);
        Instant now = at;
        boolean moveStock = "dispatch".equals(order.get("stockMode"));
        if (moveStock) {
            creditControl.assertNotOnHold((String) order.get("partnerId"));
        }
        BigDecimal tonnes = BigDecimal.ZERO;
        boolean knownT = false;
        BigDecimal total = BigDecimal.ZERO;
        DeliverySourcePort issuePort = moveStock && order.get("sourceType") != null
                ? orders.sourcePort((String) order.get("sourceType"), (String) order.get("sourceId")) : null;
        // Audit S-2: issue in the engine's lock order.
        for (Map<String, Object> l : StockMove.inLockOrder(lines(id), r -> (String) r.get("productId"), this::variantOf)) {
            BigDecimal qty = l.get("loadedQty") != null ? SqlRows.dec(l, "loadedQty") : SqlRows.dec(l, "plannedQty");
            Map<String, Object> data = Obj.of("dispatchedQty", qty, "billableQty", qty, "loadedQty", qty);
            if (qty.signum() > 0 && moveStock) {
                saleable.assertSaleable((String) l.get("productId"));
            }
            if (qty.signum() > 0 && moveStock && tracked((String) l.get("productId"))) {
                if (issuePort != null) {
                    // Sales readiness DEL-8: never issue stock firmly reserved for another order.
                    String sourceLine = db.jdbc().sql("SELECT \"sourceLineId\" FROM \"FleetDeliveryOrderLine\" WHERE id = ?"
                            + " AND \"organizationId\" = ?").params(l.get("deliveryOrderLineId"), FleetDb.org())
                            .query(String.class).optional().orElse(null);
                    BigDecimal factor = l.get("uomFactor") == null || SqlRows.dec(l, "uomFactor").signum() <= 0
                            ? BigDecimal.ONE : SqlRows.dec(l, "uomFactor");
                    issuePort.assertIssuable(sourceLine, (String) l.get("productId"), variantOf(l),
                            (String) t.get("locationId"), qty.multiply(factor));
                }
                String batch = (String) l.get("batchNumber");
                StockMove m = StockMove.of((String) l.get("productId"), (String) t.get("locationId"), qty)
                        .variant(variantOf(l)).uom((String) l.get("uomId"), SqlRows.dec(l, "uomFactor")).batch(batch)
                        .moveType("issue").source(STOCK_SOURCE, id).reference(code).date(now)
                        .notes("Delivery " + order.get("code") + " · trip " + code)
                        .baseQuantity(cumulativeBase(l, qty));
                if (batch != null) {
                    m.strategy("MANUAL");
                }
                List<String> serials = serials((String) l.get("id"));
                if (!serials.isEmpty()) {
                    m.serials(serials);
                }
                StockMove.Issued issued = stock.issue(m);
                data.put("stockLedgerCode", issued.ledgerCode());
                data.put("unitCost", issued.unitCost());
                data.put("stockValue", issued.totalValue());
                total = total.add(issued.totalValue());
            }
            db.update(FleetLoadLine.class, (String) l.get("id"), data);
            history.record(l, "dispatched", "dispatched", BigDecimal.ZERO, qty, "Dispatched on trip " + code, null,
                    "trip", id);
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
        data.putAll(startMeter(t, odometer, now));
        Map<String, Object> after = db.update(FleetLoad.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "issue", Obj.of("status", t.get("status")),
                Obj.of("status", "dispatched", "stockValue", JsNumbers.dec(total), "deliveryNoteNumber",
                        after.get("deliveryNoteNumber"))));
        orders.refreshStatus((String) order.get("id"));
        events.publishOnCommit(DomainEvents.FLEET_TRIP_DISPATCHED, tripEvent(after));
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
            BigDecimal damaged = p.damagedQty() == null ? BigDecimal.ZERO : p.damagedQty();
            if (returned.compareTo(dispatched) > 0) {
                throw ApiException.badRequest("More is returned than was dispatched on trip " + code);
            }
            if (returned.add(damaged).compareTo(dispatched) > 0) {
                throw ApiException.badRequest("Returned and damaged quantities exceed what was dispatched on trip "
                        + code);
            }
            // Phase A A3 (plan §3): damaged is a physical fact of its own; the POD shortage is D − R − G − Δ.
            BigDecimal shortQty = dispatched.subtract(delivered).subtract(returned).subtract(damaged);
            String productName = productName((String) l.get("productId"));
            if ((shortQty.signum() != 0 || returned.signum() > 0 || damaged.signum() > 0) && p.reason() == null) {
                throw ApiException.badRequest("A reason is required for the difference on " + productName);
            }
            Map<String, Object> data = Obj.of("deliveredQty", delivered, "returnedQty", returned, "damagedQty", damaged);
            if (returned.signum() > 0) {
                String ledger = putBack(t, order, l, returned, "Returned at delivery of trip " + code,
                        returnedSerials(l, p.returnedSerialNumbers(), returned, dispatched), rejectLocation(order, t));
                data.put("returnLedgerCode", ledger);
                diffs.add(new Diff(l, "returned", p.reason(), dispatched, delivered, returned, p.notes()));
            }
            if (damaged.signum() > 0) {
                diffs.add(new Diff(l, "damaged", "damaged", dispatched, delivered, damaged, p.notes()));
            }
            if (shortQty.signum() != 0) {
                diffs.add(new Diff(l, shortQty.signum() > 0 ? "shortage" : "excess", p.reason(), dispatched, delivered,
                        shortQty, p.notes()));
            }
            db.update(FleetLoadLine.class, (String) l.get("id"), data);
            syncDeliveryLoss(order, l, damaged.add(shortQty.max(BigDecimal.ZERO)), dispatched, id, code);
            history.record(l, "pod_recorded", "delivered", BigDecimal.ZERO, delivered, p.reason(), null, "trip", id);
            if (returned.signum() > 0) {
                history.record(l, "returned", "returned", BigDecimal.ZERO, returned, p.reason(), null, "trip", id);
            }
            if (damaged.signum() > 0) {
                history.record(l, "damaged_recorded", "damaged", BigDecimal.ZERO, damaged, p.reason(), null, "trip", id);
            }
            if (shortQty.signum() < 0) {
                history.record(l, "excess_recorded", "excess", BigDecimal.ZERO, shortQty.negate(), p.reason(), null,
                        "trip", id);
            }
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
        quantities.refreshLoad(id);
        if (in.customerWeight() != null) {
            customerWeights.record(after, lines(id), in.customerWeight());
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", t.get("status")),
                Obj.of("status", "delivered", "receiver", in.receiverName().trim(), "discrepancies", diffs.size())));
        orders.refreshStatus((String) order.get("id"));
        events.publishOnCommit(DomainEvents.FLEET_TRIP_DELIVERED, tripEvent(after));
        return get(id);
    }

    /**
     * Phase A A2: record or correct the customer's weighing of a delivered trip (plan §7). Locks the delivery order
     * and the trip; returns the trip with {@code customerWeight} (the frozen comparison and the split).
     */
    @Transactional
    public Map<String, Object> recordCustomerWeight(String id, DispatchCommands.CustomerWeightInput in) {
        Locked k = lockTrip(id);
        Map<String, Object> t = k.trip();
        if (!List.of("delivered", "closed").contains(t.get("status"))) {
            throw ApiException.badRequest("Record the proof of delivery of trip " + t.get("code")
                    + " before the customer weight");
        }
        Map<String, Object> result = customerWeights.record(t, lines(id), in);
        Map<String, Object> out = get(id);
        out.put("customerWeight", result);
        return out;
    }

    /** Phase A A2: a photo or scan of the customer's weighbridge ticket, kept with the POD evidence. */
    @Transactional
    public Map<String, Object> customerWeightPhoto(String id, String filename, String contentType, byte[] bytes) {
        Map<String, Object> t = require(id, true);
        if (contentType == null || !(contentType.startsWith("image/") || "application/pdf".equals(contentType))) {
            throw ApiException.badRequest("Customer weight evidence must be an image or a PDF");
        }
        Map<String, Object> pod = SqlRows.one(FleetProofOfDelivery.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetProofOfDelivery\" WHERE \"organizationId\" = ? AND \"loadId\" = ? FOR UPDATE")
                .params(FleetDb.org(), id));
        if (pod == null) {
            throw ApiException.badRequest("Record the proof of delivery of trip " + t.get("code") + " first");
        }
        Map<String, Object> file = files.upload(filename, contentType, bytes, OWNER_TYPE, id);
        db.jdbc().sql("UPDATE \"FleetProofOfDelivery\" SET \"customerWeightFileIds\" = array_append("
                + "\"customerWeightFileIds\", ?), \"updatedAt\" = " + FleetDb.NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(file.get("id"), pod.get("id"), FleetDb.org()).update();
        audit.recordInTx(AuditInput.of("FleetProofOfDelivery", (String) pod.get("id"), "update", null,
                Obj.of("customerWeightFile", file.get("id"))));
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
            BigDecimal minimum = t.get("transporterRateId") == null ? null : db.jdbc().sql("SELECT \"minimumCharge\""
                    + " FROM \"FleetTransporterRate\" WHERE id = ?").param(t.get("transporterRateId"))
                    .query(BigDecimal.class).optional().orElse(null);
            data.put("freightAmount", FreightCalculator.amount((String) t.get("freightBasis"),
                    (BigDecimal) t.get("freightRate"), tonnes, km, (BigDecimal) t.get("plannedM3"), minimum));
        }
        Map<String, Object> after = db.update(FleetLoad.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", t.get("status")), after));
        orders.refreshStatus((String) order.get("id"));
        Map<String, Object> ev = tripEvent(after);
        ev.put("distanceKm", after.get("distanceKm") == null ? null : JsNumbers.dec((BigDecimal) after.get("distanceKm")));
        ev.put("freightAmount", after.get("freightAmount") == null ? null
                : JsNumbers.dec((BigDecimal) after.get("freightAmount")));
        events.publishOnCommit(DomainEvents.FLEET_TRIP_CLOSED, ev);
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
                data.put("returnLedgerCode", putBack(t, order, l, qty, "Load returned: trip " + code,
                        serials((String) l.get("id")), REJECTION_CODES.contains(in.reasonCode()) ? rejectLocation(order, t)
                                : (String) t.get("locationId")));
            }
            db.update(FleetLoadLine.class, (String) l.get("id"), data);
            if (qty.signum() > 0) {
                history.record(l, "returned", "returned", BigDecimal.ZERO, qty, in.reason().trim(), null, "trip", id);
            }
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
        quantities.refreshLoad(id);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "receive", Obj.of("status", t.get("status")),
                Obj.of("status", "returned", "reason", in.reason().trim())));
        orders.refreshStatus((String) order.get("id"));
        Map<String, Object> ev = tripEvent(after);
        ev.put("reason", in.reason().trim());
        events.publishOnCommit(DomainEvents.FLEET_TRIP_RETURNED, ev);
        return get(id);
    }

    /** Count a delivery-note print (the PDF says COPY from the second print on). Returns the print number. */
    @Transactional
    public int countDeliveryNotePrint(String id) {
        return deliveryNotePrint(id, true);
    }

    /**
     * The copy number of a delivery note: counted (and stored) for a print; for a view (sales readiness DEL-12) the
     * last printed copy number, at least 1, without counting.
     */
    @Transactional
    public int deliveryNotePrint(String id, boolean count) {
        Map<String, Object> t = require(id, count);
        if (t.get("deliveryNoteNumber") == null) {
            throw ApiException.badRequest("The delivery note of trip " + t.get("code") + " is printed once it is"
                    + " dispatched");
        }
        int printed = ((Number) t.get("deliveryNotePrints")).intValue();
        if (!count) {
            return Math.max(printed, 1);
        }
        int n = printed + 1;
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

    /** Product variant of a load line, from its delivery-order line (ADR-S058; null without variants). */
    private String variantOf(Map<String, Object> loadLine) {
        Object dl = loadLine.get("deliveryOrderLineId");
        if (dl == null) {
            return null;
        }
        return db.jdbc().sql("SELECT \"variantId\" FROM \"FleetDeliveryOrderLine\" WHERE id = ? AND \"organizationId\" = ?")
                .params(dl, FleetDb.org()).query(String.class).optional().orElse(null);
    }

    /**
     * Sales readiness R4d (DEL-2): record the serial numbers a trip line will issue (serial-tracked goods), before it
     * is dispatched. {@code byLine}: load line id → serial numbers (base units). An empty list clears them.
     */
    @Transactional
    public void setSerials(String tripId, Map<String, List<String>> byLine) {
        Locked k = lockTrip(tripId);
        if (k.trip().get("stockIssuedAt") != null) {
            throw ApiException.conflict("Trip " + k.trip().get("code") + " already issued its stock");
        }
        for (Map.Entry<String, List<String>> e : byLine.entrySet()) {
            List<String> list = e.getValue() == null ? List.of() : e.getValue().stream().map(String::trim)
                    .filter(x -> !x.isEmpty()).toList();
            if (new java.util.HashSet<>(list).size() != list.size()) {
                throw ApiException.badRequest("A serial number is entered twice");
            }
            int n = db.jdbc().sql("UPDATE \"FleetLoadLine\" SET \"serialNumbers\" = ?::jsonb WHERE id = ? AND \"loadId\" = ?"
                    + " AND \"organizationId\" = ?").params(list.isEmpty() ? null : SERIALS_JSON.writeValueAsString(list),
                            e.getKey(), tripId, FleetDb.org()).update();
            if (n == 0) {
                throw ApiException.badRequest("Line " + e.getKey() + " is not on trip " + k.trip().get("code"));
            }
        }
    }

    private static final tools.jackson.databind.json.JsonMapper SERIALS_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** Serial numbers recorded on a trip line (empty when none). */
    List<String> serials(String loadLineId) {
        String raw = db.jdbc().sql("SELECT \"serialNumbers\"::text FROM \"FleetLoadLine\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(loadLineId, FleetDb.org()).query(String.class).optional().orElse(null);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(SERIALS_JSON.readValue(raw, String[].class));
    }

    /** The serials handed back at delivery: all of them when everything came back, else the ones named (a subset). */
    private List<String> returnedSerials(Map<String, Object> l, List<String> named, BigDecimal returned,
            BigDecimal dispatched) {
        List<String> issued = serials((String) l.get("id"));
        if (issued.isEmpty()) {
            return List.of();
        }
        if (named == null || named.isEmpty()) {
            if (returned.compareTo(dispatched) == 0) {
                return issued;
            }
            throw ApiException.badRequest("Name the serial numbers that came back on "
                    + productName((String) l.get("productId")));
        }
        for (String sn : named) {
            if (!issued.contains(sn)) {
                throw ApiException.badRequest("Serial number " + sn + " was not dispatched on this line");
            }
        }
        return named;
    }

    private List<Map<String, Object>> lines(String tripId) {
        return db.list(FleetLoadLine.class, " AND \"loadId\" = ? ORDER BY \"createdAt\", id", tripId);
    }

    /** Receive returned goods back into the trip's yard at the issue cost (current cost in stock mode invoiced). */
    private String putBack(Map<String, Object> t, Map<String, Object> order, Map<String, Object> l, BigDecimal qty,
            String notes, List<String> serials) {
        return putBack(t, order, l, qty, notes, serials, (String) t.get("locationId"));
    }

    private String putBack(Map<String, Object> t, Map<String, Object> order, Map<String, Object> l, BigDecimal qty,
            String notes, List<String> serials, String location) {
        if (!tracked((String) l.get("productId"))) {
            return null;
        }
        boolean issuedHere = l.get("stockLedgerCode") != null;
        if ("dispatch".equals(order.get("stockMode")) && !issuedHere) {
            return null;
        }
        StockMove m = StockMove.of((String) l.get("productId"), location, qty)
                .variant(variantOf(l)).uom((String) l.get("uomId"), SqlRows.dec(l, "uomFactor"))
                .batch((String) l.get("batchNumber")).source(STOCK_SOURCE + "_return", (String) t.get("id")).reference((String) t.get("code")).notes(notes);
        if (issuedHere && l.get("unitCost") != null) {
            m.unitCost(SqlRows.dec(l, "unitCost"));
        }
        if (serials != null && !serials.isEmpty()) {
            m.serials(serials);
        }
        return stock.receiveReturn(m).ledgerCode();
    }

    /** Return reasons that mean the customer rejected the goods (they go to quarantine, DEL-9b). */
    private static final java.util.Set<String> REJECTION_CODES = java.util.Set.of("customer_rejected", "wrong_product",
            "damaged");

    /** Where rejected goods of this trip come back: the source's choice (sales: quarantine), else the trip location. */
    private String rejectLocation(Map<String, Object> order, Map<String, Object> t) {
        String here = (String) t.get("locationId");
        DeliverySourcePort port = order.get("sourceType") == null ? null
                : orders.sourcePort((String) order.get("sourceType"), (String) order.get("sourceId"));
        return port == null ? here : port.rejectedGoodsLocation(here);
    }

    /**
     * Sales readiness DEL-9a: book the cost of what was damaged or is missing at delivery out of cost of goods sold
     * into the source's loss account, pro rata of the line's issue value; only the change against what this line
     * already booked is posted (a corrected proof of delivery reverses part of it).
     */
    private void syncDeliveryLoss(Map<String, Object> order, Map<String, Object> l, BigDecimal lossQty,
            BigDecimal dispatched, String tripId, String code) {
        if (stockPosting == null || l.get("stockLedgerCode") == null || l.get("stockValue") == null
                || dispatched.signum() <= 0) {
            return;
        }
        DeliverySourcePort port = order.get("sourceType") == null ? null
                : orders.sourcePort((String) order.get("sourceType"), (String) order.get("sourceId"));
        String account = port == null ? null : port.deliveryLossAccount();
        if (account == null) {
            return;
        }
        String lineId = (String) l.get("id");
        BigDecimal booked = db.jdbc().sql("SELECT \"lossValue\" FROM \"FleetLoadLine\" WHERE id = ? AND \"organizationId\""
                + " = ?").params(lineId, FleetDb.org()).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        BigDecimal target = SqlRows.dec(l, "stockValue").multiply(lossQty.min(dispatched))
                .divide(dispatched, 2, RoundingMode.HALF_UP);
        BigDecimal diff = target.subtract(booked);
        if (diff.signum() == 0) {
            return;
        }
        stockPosting.postDeliveryLoss((String) l.get("productId"), diff, Instant.now(), "fleet_trip", tripId,
                "delivery-loss:" + lineId + ":" + target.toPlainString(), account, "Delivery loss · trip " + code);
        db.jdbc().sql("UPDATE \"FleetLoadLine\" SET \"lossValue\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(target, lineId, FleetDb.org()).update();
    }

    private com.inventoryerp.inventory.application.StockPostingService stockPosting;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStockPosting(com.inventoryerp.inventory.application.StockPostingService stockPosting) {
        this.stockPosting = stockPosting;
    }

    private void assertAssigned(Map<String, Object> t) {
        if ("own".equals(t.get("carrier"))) {
            if (t.get("vehicleId") == null || t.get("driverId") == null) {
                throw ApiException.badRequest("Assign a truck and a driver to trip " + t.get("code") + " first");
            }
        } else if ("manual".equals(t.get("carrier"))) {
            // Sales delivery without the Fleet module (SG-9): vehicle and driver are typed in, no fleet record exists.
            // A customer collection or a courier hand-off may leave without a plate of ours.
            String method = db.jdbc().sql("SELECT \"deliveryMethod\" FROM \"FleetLoad\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(t.get("id"), FleetDb.org()).query(String.class).optional()
                    .orElse(null);
            boolean plateOptional = "customer_collect".equals(method) || "courier".equals(method);
            if (t.get("transporterPlate") == null && !plateOptional) {
                throw ApiException.badRequest("Enter the vehicle of trip " + t.get("code") + " first");
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

    private Map<String, Object> startMeter(Map<String, Object> t, BigDecimal odometer, Instant at) {
        if (odometer == null || t.get("startReadingId") != null || t.get("vehicleId") == null) {
            return Map.of();
        }
        Map<String, Object> r = meters.recordAutomated((String) t.get("vehicleId"), "odometer", odometer, at,
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
