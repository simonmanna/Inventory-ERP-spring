package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.ProjectService.blank;
import static com.inventoryerp.projects.application.ProjectService.org;

import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.fleet.application.FleetCostCalculator;
import com.inventoryerp.fleet.application.FleetCostCalculator.Allocation;
import com.inventoryerp.fleet.application.FleetCostingService;
import com.inventoryerp.fleet.application.FleetCostingService.VehicleCost;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fleet trips and fleet work orders charged to a project (ADR-S075 §3). A trip ({@code FleetLoad}) or work order
 * tagged with the project — or, for a trip, delivering to a customer site linked to it — is charged once:
 *
 * <ul>
 *   <li><b>trip</b> (own fleet, delivered / closed): the vehicle's plant rate in force on the delivery date × the
 *       trip's km, hours (dispatch → delivery) or 1 day; without a rate, the trip's share of the unit's fleet cost
 *       for the month (fleet costing allocation basis).</li>
 *   <li><b>work order</b> (completed / closed): the net of its posted cost lines.</li>
 * </ul>
 * Posting: Dr 1460 project WIP (equipment element) / Cr plant recovery on the unit's cost centre. Reversal goes through
 * the normal cost-line reversal, which frees the source for a new charge.
 */
@Service
public class ProjectFleetChargeService {

    private final JdbcClient jdbc;
    private final ProjectCostService costs;
    private final ProjectPlantRateService plantRates;
    private final FleetCostingService fleetCosting;
    private final AuditService audit;

    public record FleetChargeInput(String sourceType, String sourceId, String wbsId, String siteId, String date,
            String notes) {}

    public ProjectFleetChargeService(JdbcClient jdbc, ProjectCostService costs, ProjectPlantRateService plantRates,
            FleetCostingService fleetCosting, AuditService audit) {
        this.jdbc = jdbc;
        this.costs = costs;
        this.plantRates = plantRates;
        this.fleetCosting = fleetCosting;
        this.audit = audit;
    }

    /** Trips and work orders tagged with the project that are not charged yet. */
    @Transactional(readOnly = true)
    public Map<String, Object> candidates(String projectId) {
        costs.projectRow(projectId);
        List<Map<String, Object>> trips = SqlRows.list(jdbc.sql("SELECT l.id, l.code, l.status, l.\"vehicleId\","
                + " v.code AS \"vehicleCode\", l.\"distanceKm\", l.\"deliveredAt\", coalesce(l.\"wbsId\", NULL) AS \"wbsId\""
                + " FROM \"FleetLoad\" l JOIN \"FleetDeliveryOrder\" o ON o.id = l.\"deliveryOrderId\" LEFT JOIN"
                + " \"FleetVehicle\" v ON v.id = l.\"vehicleId\" WHERE l.\"organizationId\" = ? AND coalesce(l.\"projectId\","
                + " o.\"projectId\") = ? AND l.carrier = 'own' AND NOT EXISTS (SELECT 1 FROM \"ProjectCostLine\" c WHERE"
                + " c.\"sourceType\" = 'project_fleet' AND c.status = 'posted' AND c.\"sourceLineId\" = l.id)"
                + " ORDER BY l.\"deliveredAt\" NULLS LAST, l.code").params(org(), projectId));
        List<Map<String, Object>> workOrders = SqlRows.list(jdbc.sql("SELECT w.id, w.code, w.status, w.\"vehicleId\","
                + " v.code AS \"vehicleCode\", w.\"wbsId\", (SELECT coalesce(sum(x.amount), 0) FROM \"FleetWorkOrderLine\" x"
                + " WHERE x.\"workOrderId\" = w.id AND x.status = 'posted') AS amount FROM \"FleetWorkOrder\" w LEFT JOIN"
                + " \"FleetVehicle\" v ON v.id = w.\"vehicleId\" WHERE w.\"organizationId\" = ? AND w.\"projectId\" = ? AND"
                + " NOT EXISTS (SELECT 1 FROM \"ProjectCostLine\" c WHERE c.\"sourceType\" = 'project_fleet' AND c.status ="
                + " 'posted' AND c.\"sourceLineId\" = w.id) ORDER BY w.code").params(org(), projectId));
        return Obj.of("trips", trips, "workOrders", workOrders);
    }

    /** Charge one tagged trip or work order to the project (once). */
    @Transactional
    public Map<String, Object> charge(String projectId, FleetChargeInput in) {
        Map<String, Object> p = costs.capturable(projectId);
        String type = in.sourceType();
        if (!"trip".equals(type) && !"work_order".equals(type)) {
            throw ApiException.badRequest("sourceType must be one of the following values: trip, work_order");
        }
        if (blank(in.sourceId()) == null) {
            throw ApiException.badRequest("sourceId should not be empty");
        }
        Charge c = "trip".equals(type) ? trip(projectId, in.sourceId()) : workOrder(projectId, in.sourceId());
        String wbsId = blank(in.wbsId()) != null ? in.wbsId() : c.wbsId();
        if (wbsId == null) {
            throw ApiException.badRequest("wbsId should not be empty");
        }
        Map<String, Object> w = costs.costableWbs(projectId, wbsId);
        String siteId = costs.site(projectId, in.siteId());
        if (jdbc.sql("SELECT count(*) FROM \"ProjectCostLine\" WHERE \"sourceType\" = 'project_fleet' AND status ="
                + " 'posted' AND \"sourceLineId\" = ?").param(c.id()).query(Long.class).single() > 0) {
            throw ApiException.conflict(c.label() + " is already charged to a project");
        }
        if (c.amount().signum() <= 0) {
            throw ApiException.badRequest("No plant rate or fleet cost is available for " + c.label());
        }
        Instant date = blank(in.date()) != null ? ProjectCostService.instant(in.date())
                : c.date().atStartOfDay(ZoneOffset.UTC).toInstant();
        String id = UUID.randomUUID().toString();
        String text = "Project " + p.get("code") + " · " + w.get("code") + " · " + c.label();
        String credit = costs.plantRecoveryAccount();
        String je = costs.post(List.of(costs.debit(p, w, siteId, c.amount(), text), new Line(credit, null,
                JsNumbers.dec(c.amount()), null, text, ProjectCostService.branch(p), c.costCenterId(),
                Obj.of("projectId", projectId, "vehicleId", c.vehicleId(), "fleetSourceType", type, "fleetSourceId",
                        c.id()))), text, date, "project_fleet", id, p);
        costs.insert(id, projectId, (String) w.get("id"), siteId, "equipment", "project_fleet", id, c.id(), date,
                c.quantity(), c.rate(), c.amount(), false, credit, je, blank(in.notes()) == null ? text
                        : in.notes() + " · " + c.basis());
        audit.recordInTx(AuditInput.of("ProjectCostLine", id, "create", null, Obj.of("fleetSourceType", type,
                "fleetSourceId", c.id(), "basis", c.basis(), "amount", c.amount())));
        Map<String, Object> out = costs.line(id);
        out.put("basis", c.basis());
        return out;
    }

    record Charge(String id, String label, String vehicleId, String wbsId, String costCenterId, LocalDate date,
            BigDecimal quantity, BigDecimal rate, BigDecimal amount, String basis) {}

    private Charge trip(String projectId, String tripId) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT l.*, coalesce(l.\"projectId\", o.\"projectId\") AS"
                + " \"chargeProjectId\", v.\"costCenterId\" AS \"vehicleCostCenterId\" FROM \"FleetLoad\" l JOIN"
                + " \"FleetDeliveryOrder\" o ON o.id = l.\"deliveryOrderId\" LEFT JOIN \"FleetVehicle\" v ON v.id ="
                + " l.\"vehicleId\" WHERE l.id = ? AND l.\"organizationId\" = ? FOR UPDATE OF l").params(tripId, org()));
        if (t == null) {
            throw ApiException.notFound("Trip not found");
        }
        String label = "trip " + t.get("code");
        if (!projectId.equals(t.get("chargeProjectId"))) {
            throw ApiException.badRequest("Trip " + t.get("code") + " is not tagged with this project");
        }
        if (!"own".equals(t.get("carrier")) || t.get("vehicleId") == null) {
            throw ApiException.badRequest("Only an own-fleet trip with a vehicle can be charged; transporter freight is"
                    + " reclassed from its bill");
        }
        if (!List.of("delivered", "closed").contains(String.valueOf(t.get("status")))) {
            throw ApiException.badRequest("Trip " + t.get("code") + " is not delivered yet");
        }
        String vehicle = (String) t.get("vehicleId");
        Instant delivered = instant(t.get("deliveredAt"));
        LocalDate day = (delivered == null ? Instant.now() : delivered).atZone(ZoneOffset.UTC).toLocalDate();
        BigDecimal km = (BigDecimal) t.get("distanceKm");
        BigDecimal tonnes = t.get("deliveredTonnes") != null ? (BigDecimal) t.get("deliveredTonnes")
                : (BigDecimal) t.get("loadedTonnes");
        Map<String, Object> rate = plantRates.resolve(vehicle, null, day);
        String costCenter = rate != null && rate.get("costCenterId") != null ? (String) rate.get("costCenterId")
                : (String) t.get("vehicleCostCenterId");
        if (rate != null) {
            String unit = (String) rate.get("unit");
            BigDecimal qty = switch (unit) {
                case "km" -> km;
                case "hour" -> hours(instant(t.get("dispatchedAt")) == null ? instant(t.get("departedAt"))
                        : instant(t.get("dispatchedAt")), delivered);
                default -> BigDecimal.ONE;
            };
            if (qty == null || qty.signum() <= 0) {
                throw ApiException.badRequest("Trip " + t.get("code") + " has no " + ("km".equals(unit) ? "distance"
                        : "dispatch-to-delivery time") + " for its " + unit + " rate");
            }
            BigDecimal r = SqlRows.dec(rate, "rate");
            return new Charge(tripId, label, vehicle, (String) t.get("wbsId"), costCenter, day, qty, r,
                    qty.multiply(r).setScale(2, RoundingMode.HALF_UP), "plant_rate_" + unit);
        }
        FleetCostingService.Period period = FleetCostingService.period(day.withDayOfMonth(1),
                day.withDayOfMonth(day.lengthOfMonth()));
        List<VehicleCost> vc = fleetCosting.vehicleCosts(period, vehicle, null, null, null);
        if (vc.isEmpty()) {
            return new Charge(tripId, label, vehicle, (String) t.get("wbsId"), costCenter, day, null, null,
                    BigDecimal.ZERO, "none");
        }
        Allocation a = FleetCostCalculator.allocate(fleetCosting.allocationBasis(), vc.getFirst().rates(), km, tonnes);
        return new Charge(tripId, label, vehicle, (String) t.get("wbsId"), costCenter, day, km, null,
                a.amount().setScale(2, RoundingMode.HALF_UP), "fleet_cost_" + a.basis());
    }

    private Charge workOrder(String projectId, String workOrderId) {
        Map<String, Object> w = SqlRows.one(jdbc.sql("SELECT w.*, v.\"costCenterId\" AS \"vehicleCostCenterId\" FROM"
                + " \"FleetWorkOrder\" w LEFT JOIN \"FleetVehicle\" v ON v.id = w.\"vehicleId\" WHERE w.id = ? AND"
                + " w.\"organizationId\" = ? FOR UPDATE OF w").params(workOrderId, org()));
        if (w == null) {
            throw ApiException.notFound("Work order not found");
        }
        if (!projectId.equals(w.get("projectId"))) {
            throw ApiException.badRequest("Work order " + w.get("code") + " is not tagged with this project");
        }
        if (!List.of("completed", "closed").contains(String.valueOf(w.get("status")))) {
            throw ApiException.badRequest("Work order " + w.get("code") + " is not completed yet");
        }
        BigDecimal amount = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"FleetWorkOrderLine\" WHERE"
                + " \"workOrderId\" = ? AND status = 'posted'").param(workOrderId).query(BigDecimal.class).single()
                .setScale(2, RoundingMode.HALF_UP);
        Instant done = instant(w.get("completedAt"));
        LocalDate day = (done == null ? Instant.now() : done).atZone(ZoneOffset.UTC).toLocalDate();
        String cc = w.get("costCenterId") != null ? (String) w.get("costCenterId") : (String) w.get("vehicleCostCenterId");
        return new Charge(workOrderId, "work order " + w.get("code"), (String) w.get("vehicleId"),
                (String) w.get("wbsId"), cc, day, null, null, amount, "work_order_cost");
    }

    private static Instant instant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (v instanceof java.time.LocalDateTime l) {
            return l.toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(v.toString());
    }

    private static BigDecimal hours(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return null;
        }
        return BigDecimal.valueOf(Duration.between(from, to).toSeconds()).divide(BigDecimal.valueOf(3600), 2,
                RoundingMode.HALF_UP);
    }
}
