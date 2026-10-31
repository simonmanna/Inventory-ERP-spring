package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CompleteWorkOrder;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ClockOnInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ConfirmDowntimeInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ConfirmProductionInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.DowntimeInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.EndDowntimeInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.MeterReadingInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ProductionReportInput;
import com.inventoryerp.manufacturing.application.StageCommands.OutputQty;
import com.inventoryerp.manufacturing.application.StageCommands.StageOutput;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionOrderStage;
import com.inventoryerp.manufacturing.domain.ShopFloorClockEntry;
import com.inventoryerp.manufacturing.domain.ShopFloorDowntime;
import com.inventoryerp.manufacturing.domain.ShopFloorMeterReading;
import com.inventoryerp.manufacturing.domain.ShopFloorProductionReport;
import com.inventoryerp.manufacturing.domain.WorkOrder;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator terminal of the shop floor (ADR-S015): work-centre queue, start / pause (stop with a reason → downtime) /
 * complete of work orders through {@link WorkOrderService}, operator clock entries, downtime logging, production
 * (output / scrap) reports and meter readings, and the confirmation of machine drafts.
 *
 * <p>No stock logic lives here: a production report of a multi-stage order posts through
 * {@link StageExecutionService#output} (stock, WIP, GL, yield) and keeps the stage posting id; on a single-leg order
 * it adds to the work order's good / scrap counts (stock moves at the order's complete leg). Every record belongs to
 * the shift report of its time and is refused once that report is submitted or approved.
 */
@Service
public class ShopFloorExecutionService {

    static final String PRODUCTION_SELECT = "SELECT pr.*, w.name AS \"workOrderName\", o.\"orderCode\", p.name AS"
            + " \"productName\", s.sequence AS \"stageSequence\", s.name AS \"stageName\""
            + " FROM \"ShopFloorProductionReport\" pr LEFT JOIN \"WorkOrder\" w ON w.id = pr.\"workOrderId\""
            + " LEFT JOIN \"ProductionOrder\" o ON o.id = pr.\"productionOrderId\""
            + " LEFT JOIN \"Product\" p ON p.id = o.\"outputProductId\""
            + " LEFT JOIN \"ProductionOrderStage\" s ON s.id = pr.\"stageId\"";
    static final String DOWNTIME_SELECT = "SELECT d.*, r.name AS \"reasonName\", r.category AS \"reasonCategory\","
            + " w.name AS \"workOrderName\" FROM \"ShopFloorDowntime\" d"
            + " LEFT JOIN \"ShopFloorDowntimeReason\" r ON r.id = d.\"reasonId\""
            + " LEFT JOIN \"WorkOrder\" w ON w.id = d.\"workOrderId\"";
    static final String CLOCK_SELECT = "SELECT c.*, trim(concat(u.\"firstName\", ' ', coalesce(u.\"lastName\", '')))"
            + " AS \"userName\", w.name AS \"workOrderName\" FROM \"ShopFloorClockEntry\" c"
            + " LEFT JOIN \"User\" u ON u.id = c.\"userId\" LEFT JOIN \"WorkOrder\" w ON w.id = c.\"workOrderId\"";
    private static final Set<String> RUNNABLE_ORDER = Set.of("confirmed", "in_progress");

    private final ShopFloorSupport sf;
    private final MfgDb db;
    private final WorkOrderService workOrders;
    private final StageExecutionService stages;
    private final QualityService quality;

    public ShopFloorExecutionService(ShopFloorSupport sf, WorkOrderService workOrders, StageExecutionService stages,
            QualityService quality) {
        this.sf = sf;
        this.db = sf.db();
        this.workOrders = workOrders;
        this.stages = stages;
        this.quality = quality;
    }

    // ================================================================== queue

    /**
     * The terminal view of a work centre: its open work orders (running first) with order, product and stage,
     * staged orders here that have no work orders yet, open stops, operators clocked on, machine drafts, current shift.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> queue(String workCenterId) {
        Map<String, Object> wc = sf.requireWorkCenter(workCenterId);
        List<Map<String, Object>> items = SqlRows.list(db.jdbc().sql("SELECT w.id, w.name, w.status,"
                + " w.\"operationSequence\", w.\"plannedStartAt\", w.\"plannedEndAt\", w.\"plannedDurationMins\","
                + " w.\"startedAt\", w.\"pausedAt\", w.\"labourMins\", w.\"qtyGood\", w.\"qtyScrap\","
                + " o.id AS \"productionOrderId\", o.\"orderCode\", o.status AS \"orderStatus\", o.\"plannedQty\","
                + " o.\"outputProductId\", p.name AS \"productName\", p.code AS \"productCode\", s.id AS \"stageId\","
                + " s.sequence AS \"stageSequence\", s.status AS \"stageStatus\", s.\"qcStatus\", s.\"qcGate\","
                + " s.\"issueMode\", s.\"plannedOutputQty\", s.\"qtyGood\" AS \"stageQtyGood\", s.\"qtyScrap\" AS"
                + " \"stageQtyScrap\", s.\"isLast\" FROM \"WorkOrder\" w JOIN \"ProductionOrder\" o ON o.id ="
                + " w.\"productionOrderId\" LEFT JOIN \"Product\" p ON p.id = o.\"outputProductId\" LEFT JOIN"
                + " \"ProductionOrderStage\" s ON s.\"orderId\" = o.id AND s.\"routingOperationId\" ="
                + " w.\"routingOperationId\" WHERE w.\"organizationId\" = ? AND w.\"workCenterId\" = ? AND w.status IN"
                + " ('pending', 'in_progress', 'paused') AND o.status IN ('confirmed', 'in_progress') ORDER BY CASE"
                + " w.status WHEN 'in_progress' THEN 0 WHEN 'paused' THEN 1 ELSE 2 END, w.\"plannedStartAt\" NULLS LAST,"
                + " o.\"orderCode\", w.\"operationSequence\" LIMIT 100").params(MfgDb.org(), workCenterId));
        for (Map<String, Object> i : items) {
            i.put("multiStage", i.get("stageId") != null);
        }
        List<Map<String, Object>> pendingStages = SqlRows.list(db.jdbc().sql("SELECT s.id AS \"stageId\", s.sequence"
                + " AS \"stageSequence\", s.name, s.status AS \"stageStatus\", o.id AS \"productionOrderId\","
                + " o.\"orderCode\", o.status AS \"orderStatus\", p.name AS \"productName\" FROM \"ProductionOrderStage\""
                + " s JOIN \"ProductionOrder\" o ON o.id = s.\"orderId\" LEFT JOIN \"Product\" p ON p.id ="
                + " o.\"outputProductId\" WHERE s.\"organizationId\" = ? AND s.\"workCenterId\" = ? AND s.status IN"
                + " ('pending', 'in_progress') AND o.status IN ('confirmed', 'in_progress') AND NOT EXISTS (SELECT 1"
                + " FROM \"WorkOrder\" w WHERE w.\"productionOrderId\" = o.id AND w.\"organizationId\" ="
                + " s.\"organizationId\") ORDER BY o.\"orderCode\", s.sequence LIMIT 50").params(MfgDb.org(),
                        workCenterId));
        Map<String, Object> report = sf.findReport(workCenterId, Instant.now());
        return Obj.of("workCenter", wc, "profile", sf.profile(workCenterId), "currentShiftReport", report,
                "workOrders", items, "stagesWithoutWorkOrders", pendingStages,
                "openDowntime", SqlRows.list(db.jdbc().sql(DOWNTIME_SELECT + " WHERE d.\"organizationId\" = ? AND"
                        + " d.\"workCenterId\" = ? AND d.\"endedAt\" IS NULL AND d.status <> 'rejected' ORDER BY"
                        + " d.\"startedAt\"").params(MfgDb.org(), workCenterId)),
                "operators", SqlRows.list(db.jdbc().sql(CLOCK_SELECT + " WHERE c.\"organizationId\" = ? AND"
                        + " c.\"workCenterId\" = ? AND c.\"clockOffAt\" IS NULL ORDER BY c.\"clockOnAt\"")
                        .params(MfgDb.org(), workCenterId)),
                "productionDrafts", SqlRows.list(db.jdbc().sql(PRODUCTION_SELECT + " WHERE pr.\"organizationId\" = ?"
                        + " AND pr.\"workCenterId\" = ? AND pr.status = 'draft' ORDER BY pr.\"reportedAt\" LIMIT 50")
                        .params(MfgDb.org(), workCenterId)),
                "downtimeDrafts", SqlRows.list(db.jdbc().sql(DOWNTIME_SELECT + " WHERE d.\"organizationId\" = ? AND"
                        + " d.\"workCenterId\" = ? AND d.status = 'draft' ORDER BY d.\"startedAt\" LIMIT 50")
                        .params(MfgDb.org(), workCenterId)));
    }

    // ================================================================== start / pause / complete

    /** Start or resume a work order; resuming ends the operator stop opened by its pause. */
    @Transactional
    public Map<String, Object> start(String workOrderId) {
        Map<String, Object> wo = sf.lockWorkOrder(workOrderId);
        Map<String, Object> order = db.byId(ProductionOrder.class, str(wo, "productionOrderId"));
        if (order == null || !RUNNABLE_ORDER.contains(String.valueOf(order.get("status")))) {
            throw ApiException.badRequest("Production order " + (order == null ? "" : order.get("orderCode")) + " is "
                    + (order == null ? "missing" : order.get("status")) + "; only confirmed or in-progress orders run on"
                    + " the shop floor.");
        }
        Map<String, Object> updated = workOrders.start(workOrderId);
        closeOperatorStops(workOrderId, Instant.now());
        return updated;
    }

    /** Pause (stop) a running work order with a reason: opens an operator stop on its work centre. */
    @Transactional
    public Map<String, Object> pause(String workOrderId, String reasonCode, String notes) {
        Map<String, Object> reason = sf.reason(reasonCode);
        Map<String, Object> wo = sf.lockWorkOrder(workOrderId);
        String wcId = requireCentre(wo);
        Instant now = Instant.now();
        Map<String, Object> report = sf.ensureReport(wcId, now);
        ShopFloorSupport.assertEditable(report);
        Map<String, Object> updated = workOrders.pause(workOrderId);
        lockCentreStops(wcId);
        Map<String, Object> stop = openStop(wcId, "operator");
        if (stop == null) {
            stop = db.insert(ShopFloorDowntime.class, Obj.of("workCenterId", wcId, "workOrderId", workOrderId,
                    "shiftReportId", report == null ? null : report.get("id"), "reasonId", reason.get("id"),
                    "reasonCode", reason.get("code"), "isPlanned", reason.get("isPlanned"), "startedAt", now,
                    "source", "operator", "status", "confirmed", "notes", blank(notes), "recordedBy",
                    TenantContext.userId(), "confirmedBy", TenantContext.userId(), "confirmedAt", now));
        }
        return Obj.of("workOrder", updated, "downtime", stop);
    }

    /** Finish the operation (work-order labour is costed by {@link WorkOrderService#complete}). */
    @Transactional
    public Map<String, Object> complete(String workOrderId, String notes) {
        sf.lockWorkOrder(workOrderId);
        Map<String, Object> updated = workOrders.complete(workOrderId, new CompleteWorkOrder(null, null, null,
                blank(notes), null));
        closeOperatorStops(workOrderId, Instant.now());
        return updated;
    }

    private void closeOperatorStops(String workOrderId, Instant at) {
        db.jdbc().sql("UPDATE \"ShopFloorDowntime\" SET \"endedAt\" = ?, minutes = greatest(0, floor(extract(epoch FROM"
                + " (?::timestamp - \"startedAt\")) / 60))::int, \"updatedAt\" = " + MfgDb.NOW + " WHERE"
                + " \"organizationId\" = ? AND \"workOrderId\" = ? AND source = 'operator' AND status = 'confirmed' AND"
                + " \"endedAt\" IS NULL").params(MfgDb.param(at), MfgDb.param(at), MfgDb.org(), workOrderId).update();
    }

    private void lockCentreStops(String workCenterId) {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("sf-stop:" + workCenterId).query().listOfRows();
    }

    private Map<String, Object> openStop(String workCenterId, String source) {
        return SqlRows.one(ShopFloorDowntime.class, db.jdbc().sql("SELECT * FROM \"ShopFloorDowntime\" WHERE"
                + " \"organizationId\" = ? AND \"workCenterId\" = ? AND source = ? AND \"endedAt\" IS NULL AND status"
                + " <> 'rejected' LIMIT 1").params(MfgDb.org(), workCenterId, source));
    }

    private static String requireCentre(Map<String, Object> wo) {
        String wcId = str(wo, "workCenterId");
        if (wcId == null) {
            throw ApiException.badRequest("Work order " + wo.get("name") + " has no work centre; assign one first.");
        }
        return wcId;
    }

    // ================================================================== clock entries

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> clockEntries(String workCenterId, String userId, Boolean open, String from,
            String to, Integer page, Integer pageSize) {
        Filter f = new Filter("c");
        f.eq("workCenterId", workCenterId);
        f.eq("userId", userId);
        if (open != null) {
            f.raw(open ? "c.\"clockOffAt\" IS NULL" : "c.\"clockOffAt\" IS NOT NULL");
        }
        f.range("clockOnAt", from, to);
        return f.page("\"ShopFloorClockEntry\" c", CLOCK_SELECT, "c.\"clockOnAt\" DESC", page, pageSize);
    }

    /** Clock on at a work centre (optionally a work order); an open entry of the person elsewhere is closed first. */
    @Transactional
    public Map<String, Object> clockOn(ClockOnInput in) {
        String userId = in.userId() != null && !in.userId().isBlank() ? in.userId() : TenantContext.userId();
        if (sf.user(userId) == null) {
            throw ApiException.badRequest("User not found in this organization.");
        }
        sf.requireWorkCenter(in.workCenterId());
        if (in.workOrderId() != null) {
            Map<String, Object> wo = db.byId(WorkOrder.class, in.workOrderId());
            if (wo == null) {
                throw ApiException.notFound("Work order not found");
            }
            if (wo.get("workCenterId") != null && !in.workCenterId().equals(wo.get("workCenterId"))) {
                throw ApiException.badRequest("Work order " + wo.get("name") + " is not at this work centre.");
            }
        }
        Instant now = Instant.now();
        Map<String, Object> report = sf.ensureReport(in.workCenterId(), now);
        ShopFloorSupport.assertEditable(report);
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("sf-clock:" + userId).query().listOfRows();
        for (Map<String, Object> open : db.list(ShopFloorClockEntry.class, " AND \"userId\" = ? AND \"clockOffAt\" IS"
                + " NULL", userId)) {
            closeEntry(open, now, null);
        }
        return db.insert(ShopFloorClockEntry.class, Obj.of("userId", userId, "workCenterId", in.workCenterId(),
                "workOrderId", in.workOrderId(), "shiftReportId", report == null ? null : report.get("id"), "crewCode",
                blank(in.crewCode()), "role", blank(in.role()), "clockOnAt", now, "notes", blank(in.notes()),
                "recordedBy", TenantContext.userId()));
    }

    @Transactional
    public Map<String, Object> clockOff(String entryId, String notes) {
        Map<String, Object> e = sf.lock(ShopFloorClockEntry.class, entryId, "Clock entry not found");
        if (e.get("clockOffAt") != null) {
            throw ApiException.badRequest("Already clocked off.");
        }
        return closeEntry(e, Instant.now(), notes);
    }

    private Map<String, Object> closeEntry(Map<String, Object> e, Instant at, String notes) {
        Instant on = (Instant) e.get("clockOnAt");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clockOffAt", at.isBefore(on) ? on : at);
        data.put("minutes", (int) Math.max(0, ChronoUnit.MINUTES.between(on, at)));
        if (notes != null && !notes.isBlank()) {
            data.put("notes", notes.trim());
        }
        return db.update(ShopFloorClockEntry.class, str(e, "id"), data);
    }

    // ================================================================== production reports

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> productionReports(String workCenterId, String status, String source,
            String workOrderId, String shiftReportId, Integer page, Integer pageSize) {
        Filter f = new Filter("pr");
        f.eq("workCenterId", workCenterId);
        f.eq("status", status);
        f.eq("source", source);
        f.eq("workOrderId", workOrderId);
        f.eq("shiftReportId", shiftReportId);
        return f.page("\"ShopFloorProductionReport\" pr", PRODUCTION_SELECT, "pr.\"reportedAt\" DESC", page, pageSize);
    }

    /** Operator output / scrap report, posted at once. */
    @Transactional
    public Map<String, Object> report(ProductionReportInput in) {
        Map<String, Object> wo = sf.lockWorkOrder(in.workOrderId());
        Instant at = in.reportedAt() != null ? EntityPatch.toInstant(in.reportedAt()) : Instant.now();
        if (at.isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))) {
            throw ApiException.badRequest("Production cannot be reported in the future.");
        }
        Map<String, Object> fields = apply(wo, at, in.qtyGood(), in.qtyScrap(), blank(in.scrapReasonCode()),
                Boolean.TRUE.equals(in.completesStage()), in.outputs(), blank(in.batchNumber()), blank(in.notes()));
        fields.put("workCenterId", wo.get("workCenterId"));
        fields.put("workOrderId", wo.get("id"));
        fields.put("reportedAt", at);
        fields.put("source", "operator");
        fields.put("status", "posted");
        fields.put("notes", blank(in.notes()));
        fields.put("recordedBy", TenantContext.userId());
        fields.put("confirmedBy", TenantContext.userId());
        fields.put("confirmedAt", Instant.now());
        return view(str(db.insert(ShopFloorProductionReport.class, fields), "id"));
    }

    /** Confirm a machine count draft (counts may be corrected); posts like an operator report. */
    @Transactional
    public Map<String, Object> confirmProduction(String id, ConfirmProductionInput in) {
        Map<String, Object> pr = sf.lock(ShopFloorProductionReport.class, id, "Production report not found");
        if (!"draft".equals(pr.get("status"))) {
            throw ApiException.badRequest("Production report is already " + pr.get("status") + ".");
        }
        String woId = in.workOrderId() != null && !in.workOrderId().isBlank() ? in.workOrderId()
                : str(pr, "workOrderId");
        if (woId == null) {
            throw ApiException.badRequest("Choose the work order this count belongs to.");
        }
        Map<String, Object> wo = sf.lockWorkOrder(woId);
        if (!str(pr, "workCenterId").equals(wo.get("workCenterId"))) {
            throw ApiException.badRequest("Work order " + wo.get("name") + " is not at the work centre of this count.");
        }
        BigDecimal good = in.qtyGood() != null ? in.qtyGood() : SqlRows.dec(pr, "qtyGood");
        BigDecimal scrap = in.qtyScrap() != null ? in.qtyScrap() : SqlRows.dec(pr, "qtyScrap");
        String reason = in.scrapReasonCode() != null ? blank(in.scrapReasonCode()) : str(pr, "scrapReasonCode");
        String notes = in.notes() != null ? blank(in.notes()) : str(pr, "notes");
        Map<String, Object> fields = apply(wo, (Instant) pr.get("reportedAt"), good, scrap, reason,
                Boolean.TRUE.equals(in.completesStage()), null, blank(in.batchNumber()), notes);
        fields.put("workOrderId", woId);
        fields.put("status", "posted");
        fields.put("notes", notes);
        fields.put("confirmedBy", TenantContext.userId());
        fields.put("confirmedAt", Instant.now());
        db.update(ShopFloorProductionReport.class, id, fields);
        return view(id);
    }

    @Transactional
    public Map<String, Object> rejectProduction(String id, String reason) {
        Map<String, Object> pr = sf.lock(ShopFloorProductionReport.class, id, "Production report not found");
        if (!"draft".equals(pr.get("status"))) {
            throw ApiException.badRequest("Production report is already " + pr.get("status") + ".");
        }
        db.update(ShopFloorProductionReport.class, id, Obj.of("status", "rejected", "rejectedReason", reason.trim(),
                "confirmedBy", TenantContext.userId(), "confirmedAt", Instant.now()));
        return view(id);
    }

    /**
     * Post good / scrap of a work order: the stage engine for a multi-stage order (stock, WIP, GL), the work order's
     * counts otherwise. Returns the report columns it determined.
     */
    private Map<String, Object> apply(Map<String, Object> wo, Instant at, BigDecimal good, BigDecimal scrapIn,
            String reason, boolean completes, List<OutputQty> outputs, String batch, String notes) {
        String wcId = requireCentre(wo);
        BigDecimal scrap = scrapIn == null ? BigDecimal.ZERO : scrapIn;
        if (good.signum() == 0 && scrap.signum() == 0 && !completes) {
            throw ApiException.badRequest("Report a good or scrap quantity.");
        }
        Map<String, Object> report = sf.ensureReport(wcId, at);
        ShopFloorSupport.assertEditable(report);
        Map<String, Object> order = db.byId(ProductionOrder.class, str(wo, "productionOrderId"));
        Map<String, Object> stage = stageOf(wo);
        String postingId = null;
        if (stage != null) {
            stages.output(str(stage, "id"), new StageOutput(good, scrap, reason, completes, outputs, batch, null,
                    at.toString(), notes));
            postingId = db.jdbc().sql("SELECT id FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ? AND"
                    + " \"stageId\" = ? AND kind = 'output' ORDER BY \"createdAt\" DESC, code DESC LIMIT 1")
                    .params(MfgDb.org(), stage.get("id")).query(String.class).single();
        } else {
            if (completes || (outputs != null && !outputs.isEmpty())) {
                throw ApiException.badRequest("Only a work order of a multi-stage order can complete a stage or report"
                        + " co-products.");
            }
            if (order == null || !RUNNABLE_ORDER.contains(String.valueOf(order.get("status")))) {
                throw ApiException.badRequest("Order " + (order == null ? "" : order.get("orderCode")) + " is "
                        + (order == null ? "missing" : order.get("status")) + "; its work orders take no more output.");
            }
            if ("cancelled".equals(wo.get("status"))) {
                throw ApiException.badRequest("Work order " + wo.get("name") + " is cancelled.");
            }
            quality.validateReason(reason);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("qtyGood", new MfgDb.Raw("\"qtyGood\" + ?", good));
            data.put("qtyScrap", new MfgDb.Raw("\"qtyScrap\" + ?", scrap));
            if (reason != null) {
                data.put("scrapReasonCode", reason);
            }
            data.put("updatedBy", TenantContext.userId());
            db.update(WorkOrder.class, str(wo, "id"), data);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("productionOrderId", wo.get("productionOrderId"));
        fields.put("stageId", stage == null ? null : stage.get("id"));
        fields.put("shiftReportId", report == null ? null : report.get("id"));
        fields.put("qtyGood", good);
        fields.put("qtyScrap", scrap);
        fields.put("scrapReasonCode", reason);
        fields.put("completesStage", completes);
        fields.put("batchNumber", batch);
        fields.put("stagePostingId", postingId);
        return fields;
    }

    private Map<String, Object> stageOf(Map<String, Object> wo) {
        if (wo.get("routingOperationId") == null) {
            return null;
        }
        List<Map<String, Object>> rows = db.list(ProductionOrderStage.class, " AND \"orderId\" = ? AND"
                + " \"routingOperationId\" = ? LIMIT 1", wo.get("productionOrderId"), wo.get("routingOperationId"));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> view(String id) {
        return SqlRows.one(db.jdbc().sql(PRODUCTION_SELECT + " WHERE pr.id = ? AND pr.\"organizationId\" = ?")
                .params(id, MfgDb.org()));
    }

    // ================================================================== downtime

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> downtime(String workCenterId, String status, String source, Boolean open,
            String from, String to, Integer page, Integer pageSize) {
        Filter f = new Filter("d");
        f.eq("workCenterId", workCenterId);
        f.eq("status", status);
        f.eq("source", source);
        if (open != null) {
            f.raw(open ? "d.\"endedAt\" IS NULL" : "d.\"endedAt\" IS NOT NULL");
        }
        f.range("startedAt", from, to);
        return f.page("\"ShopFloorDowntime\" d", DOWNTIME_SELECT, "d.\"startedAt\" DESC", page, pageSize);
    }

    /** Log a stop: closed when {@code endedAt} / {@code minutes} is given, else open (one per work centre). */
    @Transactional
    public Map<String, Object> logDowntime(DowntimeInput in) {
        Map<String, Object> reason = sf.reason(in.reasonCode());
        sf.requireWorkCenter(in.workCenterId());
        checkWorkOrder(in.workOrderId(), in.workCenterId());
        Instant now = Instant.now();
        Instant start = in.startedAt() != null ? EntityPatch.toInstant(in.startedAt()) : now;
        Instant end = in.endedAt() != null ? EntityPatch.toInstant(in.endedAt())
                : in.minutes() != null ? start.plus(in.minutes(), ChronoUnit.MINUTES) : null;
        if (start.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw ApiException.badRequest("Downtime cannot start in the future.");
        }
        if (end != null && end.isBefore(start)) {
            throw ApiException.badRequest("endedAt must not be before startedAt.");
        }
        Map<String, Object> report = sf.ensureReport(in.workCenterId(), start);
        ShopFloorSupport.assertEditable(report);
        if (end == null) {
            lockCentreStops(in.workCenterId());
            Map<String, Object> open = openStop(in.workCenterId(), "operator");
            if (open != null) {
                throw ApiException.badRequest("This work centre already has an open stop (" + open.get("reasonCode")
                        + ") since " + open.get("startedAt") + "; end it first.");
            }
        }
        Map<String, Object> row = db.insert(ShopFloorDowntime.class, Obj.of("workCenterId", in.workCenterId(),
                "workOrderId", in.workOrderId(), "shiftReportId", report == null ? null : report.get("id"), "reasonId",
                reason.get("id"), "reasonCode", reason.get("code"), "isPlanned", reason.get("isPlanned"), "startedAt",
                start, "endedAt", end, "minutes", end == null ? null : (int) ChronoUnit.MINUTES.between(start, end),
                "source", "operator", "status", "confirmed", "notes", blank(in.notes()), "recordedBy",
                TenantContext.userId(), "confirmedBy", TenantContext.userId(), "confirmedAt", now));
        return downtimeView(str(row, "id"));
    }

    @Transactional
    public Map<String, Object> endDowntime(String id, EndDowntimeInput in) {
        Map<String, Object> d = sf.lock(ShopFloorDowntime.class, id, "Downtime not found");
        if (d.get("endedAt") != null) {
            throw ApiException.badRequest("This stop has already ended.");
        }
        if ("rejected".equals(d.get("status"))) {
            throw ApiException.badRequest("This stop was rejected.");
        }
        Instant start = (Instant) d.get("startedAt");
        Instant end = in != null && in.endedAt() != null ? EntityPatch.toInstant(in.endedAt()) : Instant.now();
        if (end.isBefore(start)) {
            throw ApiException.badRequest("endedAt must not be before startedAt.");
        }
        ShopFloorSupport.assertEditable(reportOf(d));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("endedAt", end);
        data.put("minutes", (int) ChronoUnit.MINUTES.between(start, end));
        if (in != null && in.notes() != null && !in.notes().isBlank()) {
            data.put("notes", in.notes().trim());
        }
        db.update(ShopFloorDowntime.class, id, data);
        return downtimeView(id);
    }

    /** Confirm a machine stop draft with its reason. */
    @Transactional
    public Map<String, Object> confirmDowntime(String id, ConfirmDowntimeInput in) {
        Map<String, Object> d = sf.lock(ShopFloorDowntime.class, id, "Downtime not found");
        if (!"draft".equals(d.get("status"))) {
            throw ApiException.badRequest("This stop is already " + d.get("status") + ".");
        }
        String code = in.reasonCode() != null && !in.reasonCode().isBlank() ? in.reasonCode() : str(d, "reasonCode");
        if (code == null) {
            throw ApiException.badRequest("Choose a downtime reason.");
        }
        Map<String, Object> reason = sf.reason(code);
        checkWorkOrder(in.workOrderId(), str(d, "workCenterId"));
        Map<String, Object> report = sf.ensureReport(str(d, "workCenterId"), (Instant) d.get("startedAt"));
        ShopFloorSupport.assertEditable(report);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "confirmed");
        data.put("reasonId", reason.get("id"));
        data.put("reasonCode", reason.get("code"));
        data.put("isPlanned", reason.get("isPlanned"));
        data.put("shiftReportId", report == null ? null : report.get("id"));
        if (in.workOrderId() != null && !in.workOrderId().isBlank()) {
            data.put("workOrderId", in.workOrderId());
        }
        if (in.notes() != null && !in.notes().isBlank()) {
            data.put("notes", in.notes().trim());
        }
        data.put("confirmedBy", TenantContext.userId());
        data.put("confirmedAt", Instant.now());
        db.update(ShopFloorDowntime.class, id, data);
        return downtimeView(id);
    }

    @Transactional
    public Map<String, Object> rejectDowntime(String id, String reason) {
        Map<String, Object> d = sf.lock(ShopFloorDowntime.class, id, "Downtime not found");
        if (!"draft".equals(d.get("status"))) {
            throw ApiException.badRequest("This stop is already " + d.get("status") + ".");
        }
        db.update(ShopFloorDowntime.class, id, Obj.of("status", "rejected", "rejectedReason", reason.trim(),
                "confirmedBy", TenantContext.userId(), "confirmedAt", Instant.now()));
        return downtimeView(id);
    }

    private Map<String, Object> reportOf(Map<String, Object> row) {
        return row.get("shiftReportId") == null ? null
                : db.byId(com.inventoryerp.manufacturing.domain.ShopFloorShiftReport.class, str(row, "shiftReportId"));
    }

    private void checkWorkOrder(String workOrderId, String workCenterId) {
        if (workOrderId == null || workOrderId.isBlank()) {
            return;
        }
        Map<String, Object> wo = db.byId(WorkOrder.class, workOrderId);
        if (wo == null) {
            throw ApiException.notFound("Work order not found");
        }
        if (wo.get("workCenterId") != null && !workCenterId.equals(wo.get("workCenterId"))) {
            throw ApiException.badRequest("Work order " + wo.get("name") + " is not at this work centre.");
        }
    }

    Map<String, Object> downtimeView(String id) {
        return SqlRows.one(db.jdbc().sql(DOWNTIME_SELECT + " WHERE d.id = ? AND d.\"organizationId\" = ?")
                .params(id, MfgDb.org()));
    }

    // ================================================================== meter readings

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> meterReadings(String workCenterId, String meterCode, Integer page,
            Integer pageSize) {
        Filter f = new Filter("m");
        f.eq("workCenterId", workCenterId);
        f.eq("meterCode", meterCode == null ? null : meterCode.trim().toLowerCase(java.util.Locale.ROOT));
        return f.page("\"ShopFloorMeterReading\" m", "SELECT m.* FROM \"ShopFloorMeterReading\" m",
                "m.\"readAt\" DESC", page, pageSize);
    }

    /** A meter reading; counters are monotonic unless the reading is marked as a reset. */
    @Transactional
    public Map<String, Object> readMeter(MeterReadingInput in) {
        Map<String, Object> wc = sf.requireWorkCenter(in.workCenterId());
        String meter = in.meterCode().trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> profile = sf.profile(in.workCenterId());
        List<String> configured = profile == null ? List.of()
                : StageExecutionService.stringList(profile.get("meterCodes"));
        if (!configured.isEmpty() && !configured.contains(meter)) {
            throw ApiException.badRequest("Meter " + meter + " is not configured for work centre " + wc.get("code")
                    + ".");
        }
        Instant at = in.readAt() != null ? EntityPatch.toInstant(in.readAt()) : Instant.now();
        Map<String, Object> report = sf.ensureReport(in.workCenterId(), at);
        ShopFloorSupport.assertEditable(report);
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("sf-meter:" + in.workCenterId() + ":" + meter)
                .query().listOfRows();
        Map<String, Object> prev = SqlRows.one(db.jdbc().sql("SELECT reading, \"readAt\" FROM \"ShopFloorMeterReading\""
                + " WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND \"meterCode\" = ? AND \"readAt\" <= ?"
                + " ORDER BY \"readAt\" DESC, \"createdAt\" DESC LIMIT 1").params(MfgDb.org(), in.workCenterId(), meter,
                        MfgDb.param(at)));
        boolean reset = Boolean.TRUE.equals(in.isReset());
        BigDecimal delta = null;
        if (prev != null && !reset) {
            BigDecimal last = SqlRows.dec(prev, "reading");
            if (in.reading().compareTo(last) < 0) {
                throw ApiException.badRequest("Reading " + in.reading().stripTrailingZeros().toPlainString()
                        + " is below the previous reading " + last.stripTrailingZeros().toPlainString() + " of meter "
                        + meter + "; mark it as a meter reset.");
            }
            delta = in.reading().subtract(last);
        }
        return db.insert(ShopFloorMeterReading.class, Obj.of("workCenterId", in.workCenterId(), "meterCode", meter,
                "reading", in.reading(), "delta", delta, "isReset", reset, "readAt", at, "shiftReportId",
                report == null ? null : report.get("id"), "notes", blank(in.notes()), "recordedBy",
                TenantContext.userId()));
    }

    // ================================================================== helpers

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Tenant-scoped filter + page of a list route. */
    private final class Filter {
        private final String alias;
        private final StringBuilder where;
        private final List<Object> params = new ArrayList<>();

        Filter(String alias) {
            this.alias = alias;
            this.where = new StringBuilder(" WHERE " + alias + ".\"organizationId\" = ?");
            params.add(MfgDb.org());
        }

        void eq(String col, String value) {
            if (value != null && !value.isBlank()) {
                where.append(" AND ").append(alias).append(".\"").append(col).append("\" = ?");
                params.add(value);
            }
        }

        void raw(String sql) {
            where.append(" AND ").append(sql);
        }

        void range(String col, String from, String to) {
            if (from != null && !from.isBlank()) {
                where.append(" AND ").append(alias).append(".\"").append(col).append("\" >= ?");
                params.add(MfgDb.param(EntityPatch.toInstant(from)));
            }
            if (to != null && !to.isBlank()) {
                Instant t = EntityPatch.toInstant(to);
                where.append(" AND ").append(alias).append(".\"").append(col).append("\" < ?");
                params.add(MfgDb.param(to.length() <= 10 ? t.plus(1, ChronoUnit.DAYS) : t));
            }
        }

        PageResult<Map<String, Object>> page(String from, String select, String order, Integer page, Integer size) {
            int p = page == null ? 1 : page;
            int s = size == null ? 25 : size;
            long total = db.jdbc().sql("SELECT count(*) FROM " + from + where).params(params).query(Long.class).single();
            List<Object> all = new ArrayList<>(params);
            all.add(s);
            all.add((p - 1) * s);
            return PageResult.of(SqlRows.list(db.jdbc().sql(select + where + " ORDER BY " + order + " LIMIT ? OFFSET ?")
                    .params(all)), p, s, total);
        }
    }
}
