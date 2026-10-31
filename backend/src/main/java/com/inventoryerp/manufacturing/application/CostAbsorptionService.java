package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.fleet.application.FleetCostingService;
import com.inventoryerp.fleet.application.FleetReportCommands.CostQuery;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.CostAbsorptionCalculator.Amount;
import com.inventoryerp.manufacturing.application.CostAbsorptionCalculator.Rates;
import com.inventoryerp.manufacturing.application.CostAbsorptionCalculator.Usage;
import com.inventoryerp.manufacturing.application.CostingCommands.RateInput;
import com.inventoryerp.manufacturing.application.StageCommands.StageConversion;
import com.inventoryerp.manufacturing.domain.ProductionCostAbsorption;
import com.inventoryerp.manufacturing.domain.ProductionCostComponent;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import com.inventoryerp.manufacturing.domain.WorkCenterCostRate;
import com.inventoryerp.manufacturing.domain.WorkOrder;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Overhead absorption and actual conversion cost (Track C 2.8, ADR-S018). Each work centre has rates; an order's work
 * orders give actual hours (clocked crew time, else the run time) and meter deltas; the amounts not absorbed yet are
 * posted. On a multi-stage order each amount is a stage conversion cost through the ADR-S011 engine (Dr WIP / Cr the
 * rate's account or overhead absorbed), on the operation's stage, or the next open one when that stage has closed. On a
 * plain order it joins the order's overhead, and the complete leg moves it to WIP and finished goods. Absorbing twice
 * posts only the difference; reversing the stage posting frees the amount again.
 */
@Service
public class CostAbsorptionService {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    static final String SOURCE = "cost_absorption";

    private final MfgDb db;
    private final StageExecutionService stages;
    private final AuditService audit;
    private final ObjectProvider<FleetCostingService> fleet;

    public CostAbsorptionService(MfgDb db, StageExecutionService stages, AuditService audit,
            ObjectProvider<FleetCostingService> fleet) {
        this.db = db;
        this.stages = stages;
        this.audit = audit;
        this.fleet = fleet;
    }

    /** One amount still to absorb. */
    record Pending(Map<String, Object> workOrder, Map<String, Object> rate, Amount amount, BigDecimal already) {
        BigDecimal open() {
            return amount.amount().subtract(already);
        }
    }

    // ================================================================== rates

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rates() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> byCentre = new HashMap<>();
        for (Map<String, Object> r : db.list(WorkCenterCostRate.class, "")) {
            byCentre.put(str(r, "workCenterId"), r);
        }
        for (Map<String, Object> c : db.list(WorkCenter.class, " AND \"isActive\" = true ORDER BY code")) {
            Map<String, Object> rate = byCentre.get(str(c, "id"));
            Map<String, Object> row = rate == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rate);
            row.put("workCenterId", c.get("id"));
            row.put("workCenterCode", c.get("code"));
            row.put("workCenterName", c.get("name"));
            row.put("configured", rate != null);
            row.put("effectiveMachineRate", rate == null ? null : machineRate(rate));
            out.add(row);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> saveRate(String workCenterId, RateInput in) {
        if (db.byId(WorkCenter.class, workCenterId) == null) {
            throw ApiException.notFound("Work centre not found");
        }
        String vehicle = CrushingSupport.text(in.fleetVehicleId());
        if (vehicle != null && db.jdbc().sql("SELECT count(*) FROM \"FleetVehicle\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(vehicle, MfgDb.org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Vehicle not found");
        }
        String account = CrushingSupport.text(in.creditAccountId());
        if (account != null && db.jdbc().sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?")
                .params(account, MfgDb.org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Account not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labourRatePerHour", CrushingSupport.nz(in.labourRatePerHour()));
        data.put("machineRatePerHour", CrushingSupport.nz(in.machineRatePerHour()));
        data.put("overheadRatePerHour", CrushingSupport.nz(in.overheadRatePerHour()));
        data.put("overheadBasis", in.overheadBasis() != null ? in.overheadBasis() : "machine_hours");
        data.put("energyRatePerUnit", CrushingSupport.nz(in.energyRatePerUnit()));
        data.put("energyMeterCode", CrushingSupport.text(in.energyMeterCode()));
        data.put("fleetVehicleId", vehicle);
        data.put("fleetCostDays", in.fleetCostDays() != null ? in.fleetCostDays() : 90);
        data.put("creditAccountId", account);
        data.put("notes", CrushingSupport.text(in.notes()));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> existing = rate(workCenterId);
        if (existing != null) {
            return db.update(WorkCenterCostRate.class, str(existing, "id"), data);
        }
        data.put("workCenterId", workCenterId);
        return db.insert(WorkCenterCostRate.class, data);
    }

    @Transactional
    public void deleteRate(String workCenterId) {
        if (rate(workCenterId) == null) {
            throw ApiException.notFound("Cost rate not found");
        }
        db.jdbc().sql("DELETE FROM \"WorkCenterCostRate\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ?")
                .params(MfgDb.org(), workCenterId).update();
    }

    private Map<String, Object> rate(String workCenterId) {
        List<Map<String, Object>> rows = db.list(WorkCenterCostRate.class, " AND \"workCenterId\" = ?", workCenterId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The configured machine rate, or the fleet cost per hour of the linked vehicle when there is one. */
    BigDecimal machineRate(Map<String, Object> rate) {
        BigDecimal manual = dec(rate, "machineRatePerHour");
        FleetCostingService costing = fleet.getIfAvailable();
        if (rate.get("fleetVehicleId") == null || costing == null) {
            return manual;
        }
        LocalDate to = LocalDate.now(BusinessZone.get());
        LocalDate from = to.minusDays(((Number) rate.get("fleetCostDays")).longValue());
        Map<String, Object> report = costing.report(new CostQuery(from, to, "vehicle", str(rate, "fleetVehicleId"),
                null,
                null));
        if (report.get("totals") instanceof Map<?, ?> totals && totals.get("perHour") != null) {
            BigDecimal perHour = new BigDecimal(totals.get("perHour").toString());
            return perHour.signum() > 0 ? perHour : manual;
        }
        return manual;
    }

    // ================================================================== what is due

    private List<Pending> pending(String orderId) {
        List<Pending> out = new ArrayList<>();
        Map<String, Map<String, Object>> rates = new HashMap<>();
        Map<String, BigDecimal> machineRates = new HashMap<>();
        for (Map<String, Object> wo : db.list(WorkOrder.class, " AND \"productionOrderId\" = ? AND status <>"
                + " 'cancelled'"
                + " AND \"workCenterId\" IS NOT NULL ORDER BY \"operationSequence\"", orderId)) {
            String wcId = str(wo, "workCenterId");
            Map<String, Object> rate = rates.computeIfAbsent(wcId, id -> {
                Map<String, Object> r = rate(id);
                return r == null ? Map.of() : r;
            });
            if (rate.isEmpty() || dec(wo, "subcontractCost").signum() > 0) {
                continue;
            }
            BigDecimal machine = machineRates.computeIfAbsent(wcId, id -> machineRate(rate));
            Rates r = new Rates(dec(rate, "labourRatePerHour"), machine, dec(rate, "overheadRatePerHour"),
                    dec(rate, "energyRatePerUnit"), str(rate, "overheadBasis"));
            for (Amount a : CostAbsorptionCalculator.amounts(usage(wo, rate), r)) {
                BigDecimal already = absorbed(str(wo, "id"), a.kind());
                if (a.amount().compareTo(already) > 0) {
                    out.add(new Pending(wo, rate, a, already));
                }
            }
        }
        return out;
    }

    private Usage usage(Map<String, Object> wo, Map<String, Object> rate) {
        String id = str(wo, "id");
        return new Usage(labourHours(wo), runHours(wo), energyUnits(wo, rate), costed(id, "labour"),
                costed(id, "machine"));
    }

    BigDecimal runHours(Map<String, Object> wo) {
        return hours(wo.get("labourMins") instanceof Number n ? n.longValue() : 0);
    }

    /** Clocked crew hours of the work order, else its run time. */
    BigDecimal labourHours(Map<String, Object> wo) {
        long clocked = db.jdbc().sql("SELECT coalesce(sum(minutes), 0)::bigint FROM \"ShopFloorClockEntry\" WHERE"
                + " \"organizationId\" = ? AND \"workOrderId\" = ? AND \"clockOffAt\" IS NOT NULL")
                .params(MfgDb.org(), wo.get("id")).query(Long.class).single();
        return clocked > 0 ? hours(clocked) : runHours(wo);
    }

    private static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(SIXTY, 6, RoundingMode.HALF_UP);
    }

    /** Meter consumption at the centre while the work order ran. */
    private BigDecimal energyUnits(Map<String, Object> wo, Map<String, Object> rate) {
        Instant start = (Instant) wo.get("startedAt");
        if (start == null || rate.get("energyMeterCode") == null) {
            return BigDecimal.ZERO;
        }
        Instant end = wo.get("completedAt") != null ? (Instant) wo.get("completedAt") : Instant.now();
        return db.jdbc().sql("SELECT coalesce(sum(delta), 0) FROM \"ShopFloorMeterReading\" WHERE \"organizationId\" ="
                + " ?"
                + " AND \"workCenterId\" = ? AND \"meterCode\" = ? AND \"isReset\" = false AND \"readAt\" >= ?"
                + " AND \"readAt\" <= ?").params(MfgDb.org(), wo.get("workCenterId"), rate.get("energyMeterCode"),
                        MfgDb.param(start), MfgDb.param(end)).query(BigDecimal.class).single();
    }

    /** Has completing the work order already costed this kind through its resource? */
    private boolean costed(String workOrderId, String kind) {
        return db.jdbc().sql("SELECT count(*) FROM \"ProductionCostComponent\" WHERE \"organizationId\" = ?"
                + " AND \"sourceType\" = 'work_order' AND \"sourceId\" = ? AND kind = ?::\"ProductionCostKind\"")
                .params(MfgDb.org(), workOrderId, kind).query(Long.class).single() > 0;
    }

    private BigDecimal absorbed(String workOrderId, String kind) {
        return db.jdbc().sql("SELECT coalesce(sum(a.amount), 0) FROM \"ProductionCostAbsorption\" a LEFT JOIN"
                + " \"ProductionStagePosting\" p ON p.id = a.\"stagePostingId\" WHERE a.\"organizationId\" = ?"
                + " AND a.\"workOrderId\" = ? AND a.kind = ? AND p.\"reversedAt\" IS NULL")
                .params(MfgDb.org(), workOrderId, kind).query(BigDecimal.class).single();
    }

    /** The amounts an absorption would post now, without posting. */
    List<Map<String, Object>> preview(String orderId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pending p : pending(orderId)) {
            out.add(Obj.of("workOrderId", p.workOrder().get("id"), "workOrderName", p.workOrder().get("name"), "kind",
                    p.amount().kind(), "basisQty", p.amount().basis(), "rate", p.amount().rate(), "amount", p.open()));
        }
        return out;
    }

    // ================================================================== absorb

    /** Post what is due. Amounts with no open stage left are reported as skipped and stay unabsorbed. */
    @Transactional
    public Map<String, Object> absorb(String orderId) {
        Map<String, Object> order = SqlRows.one(ProductionOrder.class, db.jdbc().sql("SELECT * FROM \"ProductionOrder\""
                + " WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(orderId, MfgDb.org()));
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        if ("cancelled".equals(order.get("status"))) {
            throw ApiException.badRequest("Order " + order.get("orderCode") + " is cancelled.");
        }
        boolean staged = stages.isMultiStage(orderId);
        List<Map<String, Object>> posted = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Pending p : pending(orderId)) {
            String kind = p.amount().kind();
            BigDecimal amount = p.open();
            String stageId = null;
            String postingId = null;
            if (staged) {
                stageId = openStage(orderId, str(p.workOrder(), "routingOperationId"));
                if (stageId == null) {
                    skipped.add(Obj.of("workOrderId", p.workOrder().get("id"), "kind", kind, "amount", amount, "reason",
                            "All stages are closed"));
                    continue;
                }
                stages.conversion(stageId, new StageConversion(amount, kind, str(p.rate(), "creditAccountId"), null,
                        "Absorbed · " + p.workOrder().get("name")));
                postingId = db.jdbc().sql("SELECT id FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ?"
                        + " AND \"orderId\" = ? ORDER BY \"createdAt\" DESC, code DESC LIMIT 1")
                        .params(MfgDb.org(), orderId).query(String.class).single();
            } else {
                if (order.get("postedAt") != null) {
                    skipped.add(Obj.of("workOrderId", p.workOrder().get("id"), "kind", kind, "amount", amount, "reason",
                            "The order has posted its output"));
                    continue;
                }
                db.update(ProductionOrder.class, orderId, Obj.of("overheadCost",
                        new MfgDb.Raw("\"overheadCost\" + ?", amount)));
                db.insert(ProductionCostComponent.class, Obj.of("orderId", orderId, "kind", kind, "amount", amount,
                        "sourceType", SOURCE, "sourceId", p.workOrder().get("id"), "notes",
                        "Absorbed · " + p.workOrder().get("name")));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", orderId);
            row.put("workOrderId", p.workOrder().get("id"));
            row.put("workCenterId", p.workOrder().get("workCenterId"));
            row.put("stageId", stageId);
            row.put("stagePostingId", postingId);
            row.put("kind", kind);
            row.put("basisQty", p.amount().basis());
            row.put("rate", p.amount().rate());
            row.put("amount", amount);
            row.put("createdBy", TenantContext.userId());
            posted.add(db.insert(ProductionCostAbsorption.class, row));
            total = total.add(amount);
        }
        if (total.signum() > 0) {
            audit.recordInTx(AuditInput.of("ProductionOrder", orderId, "update", null, Obj.of("orderCode",
                    order.get("orderCode"), "absorbed", total)));
        }
        return Obj.of("orderId", orderId, "total", total, "posted", posted, "skipped", skipped);
    }

    /** The stage of the operation, or the next stage still open; the last open one when the operation has no stage. */
    private String openStage(String orderId, String routingOperationId) {
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT id, \"routingOperationId\", status FROM"
                + " \"ProductionOrderStage\" WHERE \"organizationId\" = ? AND \"orderId\" = ? ORDER BY sequence")
                .params(MfgDb.org(), orderId));
        boolean reached = routingOperationId == null;
        String lastOpen = null;
        for (Map<String, Object> s : rows) {
            reached = reached || routingOperationId.equals(s.get("routingOperationId"));
            if (!"completed".equals(s.get("status"))) {
                lastOpen = str(s, "id");
                if (reached && routingOperationId != null) {
                    return lastOpen;
                }
            }
        }
        return lastOpen;
    }
}
