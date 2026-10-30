package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.application.PrecastCommands.CreatePrecastOrder;
import com.inventoryerp.manufacturing.application.PrecastCommands.CubeBatch;
import com.inventoryerp.manufacturing.application.PrecastCommands.CuringReading;
import com.inventoryerp.manufacturing.application.PrecastCommands.GateDecision;
import com.inventoryerp.manufacturing.application.PrecastCommands.MovePallets;
import com.inventoryerp.manufacturing.application.PrecastCommands.PressingRun;
import com.inventoryerp.manufacturing.application.StageCommands.OutputQty;
import com.inventoryerp.manufacturing.application.StageCommands.StageOutput;
import com.inventoryerp.manufacturing.domain.PrecastCuringBatch;
import com.inventoryerp.manufacturing.domain.PrecastCuringReading;
import com.inventoryerp.manufacturing.domain.ProductionOrderStage;
import com.inventoryerp.manufacturing.spi.StageCheckpointResults.CheckpointDecision;
import com.inventoryerp.quality.application.QualityCommands.OpenLot;
import com.inventoryerp.quality.application.QualityLotService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Block / precast production (ADR-S014, Phase 2.4) on the multi-stage engine (ADR-S011). A precast order is a staged
 * production order of a mix design's BOM: the second-last stage is <b>batching &amp; pressing</b> (materials
 * backflushed, pressing breakage as scrap), the last stage is <b>curing</b> (QC-gated, minimum curing days). Every
 * pressing run creates a <b>curing batch</b> (pallets, curing area, bay, maturity readings); when it has cured for
 * the minimum days it is <b>cubed</b> (good / second grade / broken counted — the curing stage output, good blocks into
 * QC quarantine, second grade as an NRV by-product, broken as scrap with a reason) and the strength gate (quality
 * inspection lot or manual decision) <b>releases</b> it to finished goods. After release an optional final (e.g.
 * 28-day) inspection lot is opened when the product has an active final plan.
 */
@Service
public class PrecastProductionService implements StageGateListener {

    static final String FINAL_SOURCE = "precast_batch";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final MfgDb db;
    private final PrecastSetupService setup;
    private final ProductionService production;
    private final StageExecutionService stages;
    private final PrecastReportService reports;
    private final QualityLotService lots;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public PrecastProductionService(MfgDb db, PrecastSetupService setup, ProductionService production,
            StageExecutionService stages, PrecastReportService reports, QualityLotService lots, AuditService audit,
            PlatformTransactionManager txm) {
        this.db = db;
        this.setup = setup;
        this.production = production;
        this.stages = stages;
        this.reports = reports;
        this.lots = lots;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    // ================================================================== orders

    private static final String ORDER_SELECT = "SELECT o.id, o.\"orderCode\", o.status, o.\"plannedQty\","
            + " o.\"producedQty\", o.\"scrapQty\", o.\"outputUnitCost\", o.\"totalCost\", o.\"scheduledFor\","
            + " o.\"batchNumber\", o.\"createdAt\", o.\"outputProductId\", p.name AS \"productName\","
            + " p.sku AS \"productSku\", m.id AS \"mixDesignId\", b.code AS \"bomCode\", b.version AS \"bomVersion\","
            + " (SELECT count(*) FROM \"PrecastCuringBatch\" cb WHERE cb.\"orderId\" = o.id"
            + " AND cb.\"organizationId\" = o.\"organizationId\") AS \"curingBatches\""
            + " FROM \"ProductionOrder\" o JOIN \"PrecastMixDesign\" m ON m.\"bomId\" = o.\"bomId\""
            + " AND m.\"organizationId\" = o.\"organizationId\" JOIN \"Bom\" b ON b.id = o.\"bomId\""
            + " LEFT JOIN \"Product\" p ON p.id = o.\"outputProductId\" AND p.\"organizationId\" = o.\"organizationId\"";

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> orders(String status, String productId, String search, Integer page,
            Integer pageSize) {
        int pg = PrecastSetupService.page(page);
        int size = PrecastSetupService.size(pageSize);
        StringBuilder where = new StringBuilder(" WHERE o.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (has(status)) {
            where.append(" AND o.status = ?");
            params.add(status);
        }
        if (has(productId)) {
            where.append(" AND o.\"outputProductId\" = ?");
            params.add(productId);
        }
        if (has(search)) {
            where.append(" AND (o.\"orderCode\" ILIKE ? OR o.\"batchNumber\" ILIKE ? OR p.name ILIKE ?)");
            String like = "%" + search.trim() + "%";
            params.addAll(List.of(like, like, like));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM (" + ORDER_SELECT + where + ") x").params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((pg - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(ORDER_SELECT + where
                + " ORDER BY o.\"createdAt\" DESC, o.id LIMIT ? OFFSET ?").params(params)), pg, size, total);
    }

    /** Scale the mix design, create the staged order, set the curing gate from the mix design, confirm. */
    @Transactional
    public Map<String, Object> createOrder(CreatePrecastOrder in) {
        Map<String, Object> mix = setup.mixRow(in.mixDesignId());
        if (!"active".equals(mix.get("status"))) {
            throw ApiException.badRequest("Mix design " + mix.get("bomCode") + " v" + mix.get("version") + " is "
                    + mix.get("status") + "; activate it first.");
        }
        Map<String, Object> scaling = setup.scaleView(mix, in.basis(), in.value(), in.wholeBatches() == null
                || in.wholeBatches());
        setup.ensureSecondGrade(str(mix, "bomId"));
        BigDecimal units = (BigDecimal) scaling.get("units");
        Map<String, Object> order = production.createOrder(new CreateOrder(str(mix, "bomId"), null, null,
                in.locationId(), in.outputLocationId(), null, units, in.scheduledFor(), in.batchNumber(), null, null,
                in.notes(), null, null));
        String orderId = str(order, "id");
        stages.initialize(orderId, null);
        List<Map<String, Object>> st = stages.stages(orderId);
        if (st.size() < 2) {
            throw ApiException.badRequest("A precast routing needs a pressing stage and a curing stage.");
        }
        Map<String, Object> cure = st.getLast();
        db.update(ProductionOrderStage.class, str(cure, "id"), Obj.of("qcGate", true, "qcStatus", "pending",
                "qcCheckpointCode", mix.get("curingCheckpointCode"), "minCuringDays", mix.get("minCuringDays")));
        if (in.confirm() == null || in.confirm()) {
            production.confirmOrder(orderId);
        }
        audit.recordInTx(AuditInput.of("ProductionOrder", orderId, "create", null, Obj.of("orderCode",
                order.get("orderCode"), "precastMixDesign", mix.get("id"), "basis", in.basis(), "value", in.value(),
                "units", units)));
        Map<String, Object> out = order(orderId);
        out.put("scaling", scaling);
        return out;
    }

    /** Order detail: header, mix design, stages (gate state), curing batches and the cost / yield summary. */
    @Transactional(readOnly = true)
    public Map<String, Object> order(String orderId) {
        Map<String, Object> o = SqlRows.one(db.jdbc().sql(ORDER_SELECT + " WHERE o.\"organizationId\" = ? AND o.id = ?")
                .params(MfgDb.org(), orderId));
        if (o == null) {
            throw ApiException.notFound("Precast order not found");
        }
        Map<String, Object> mix = setup.mixRow(str(o, "mixDesignId"));
        o.put("mixDesign", mix);
        o.put("spec", setup.specFor(str(o, "outputProductId")));
        List<Map<String, Object>> st = stages.stages(orderId);
        List<Map<String, Object>> stageRows = new ArrayList<>();
        for (int i = 0; i < st.size(); i++) {
            Map<String, Object> s = new LinkedHashMap<>(st.get(i));
            s.put("role", i == st.size() - 1 ? "curing" : i == st.size() - 2 ? "pressing" : "other");
            stageRows.add(s);
        }
        o.put("stages", stageRows);
        o.put("batches", batchRows(" AND b.\"orderId\" = ?", List.of(orderId), " ORDER BY b.\"startedAt\", b.code",
                null, null));
        o.put("costing", reports.orderCosting(orderId, mix));
        o.put("scaling", setup.scaleView(mix, "units", dec(o, "plannedQty"), false));
        return o;
    }

    /** A pressing run: the pressing stage output (backflush, breakage) and a new curing batch. */
    @Transactional
    public Map<String, Object> press(String orderId, PressingRun in) {
        Map<String, Object> o = order(orderId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> st = (List<Map<String, Object>>) o.get("stages");
        if (st.size() < 2) {
            throw ApiException.badRequest("A precast routing needs a pressing stage and a curing stage.");
        }
        Map<String, Object> press = st.get(st.size() - 2);
        Map<String, Object> cure = st.getLast();
        @SuppressWarnings("unchecked")
        Map<String, Object> mix = (Map<String, Object>) o.get("mixDesign");
        String areaId = in.curingAreaId() != null ? in.curingAreaId() : str(mix, "defaultCuringAreaId");
        if (areaId != null) {
            Map<String, Object> area = setup.requireArea(areaId);
            if (!Boolean.TRUE.equals(area.get("isActive"))) {
                throw ApiException.badRequest("Curing area " + area.get("code") + " is inactive.");
            }
        }
        BigDecimal good = in.qtyGood();
        BigDecimal broken = in.qtyBroken() == null ? BigDecimal.ZERO : in.qtyBroken();
        long n = db.jdbc().sql("SELECT count(*) FROM \"PrecastCuringBatch\" WHERE \"organizationId\" = ?"
                + " AND \"orderId\" = ?").params(MfgDb.org(), orderId).query(Long.class).single();
        String code = o.get("orderCode") + "-C" + String.format("%02d", n + 1);
        String batchNumber = has(in.batchNumber()) ? in.batchNumber().trim()
                : has(str(o, "batchNumber")) && n == 0 ? str(o, "batchNumber") : code;
        Map<String, Object> res = stages.output(str(press, "id"), new StageOutput(good, broken, in.scrapReasonCode(),
                Boolean.TRUE.equals(in.lastRun()), null, null, null, in.date(), in.notes() != null ? in.notes()
                : "Pressing run " + code));
        @SuppressWarnings("unchecked")
        String postingId = str((Map<String, Object>) res.get("posting"), "id");
        Map<String, Object> batch = null;
        if (good.signum() > 0) {
            Instant started = in.date() == null || in.date().isEmpty() ? Instant.now() : EntityPatch.toInstant(in.date());
            int minDays = ((Number) cure.get("minCuringDays")).intValue();
            Map<String, Object> spec = setup.specFor(str(o, "outputProductId"));
            int perPallet = spec == null ? 1 : ((Number) spec.get("unitsPerPallet")).intValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("orderId", orderId);
            row.put("stageId", cure.get("id"));
            row.put("pressingPostingId", postingId);
            row.put("productId", o.get("outputProductId"));
            row.put("batchNumber", batchNumber);
            row.put("quantity", good);
            row.put("brokenAtPress", broken);
            row.put("cycles", in.cycles());
            row.put("pallets", in.pallets() != null ? in.pallets() : (int) BatchScaler.ceil(good, perPallet));
            row.put("curingAreaId", areaId);
            row.put("palletLocation", PrecastSetupService.trim(in.palletLocation()));
            row.put("shift", PrecastSetupService.trim(in.shift()));
            row.put("startedAt", started);
            row.put("minCuringDays", minDays);
            row.put("curingUntil", started.plus(Duration.ofDays(minDays)));
            row.put("status", "curing");
            row.put("notes", PrecastSetupService.trim(in.notes()));
            row.put("createdBy", TenantContext.userId());
            batch = db.insert(PrecastCuringBatch.class, row);
            audit.recordInTx(AuditInput.of("PrecastCuringBatch", str(batch, "id"), "create", null, Obj.of("code", code,
                    "orderCode", o.get("orderCode"), "quantity", good, "broken", broken)));
        }
        if (Boolean.TRUE.equals(in.lastRun()) && good.signum() == 0) {
            long open = db.jdbc().sql("SELECT count(*) FROM \"PrecastCuringBatch\" b JOIN \"ProductionStagePosting\""
                    + " pp ON pp.id = b.\"pressingPostingId\" WHERE b.\"organizationId\" = ? AND b.\"orderId\" = ?"
                    + " AND b.status = 'curing' AND pp.\"reversedAt\" IS NULL").params(MfgDb.org(), orderId)
                    .query(Long.class).single();
            if (open == 0 && !"completed".equals(cure.get("status"))) {
                // Every batch is already cubed: close the curing stage too.
                stages.output(str(cure, "id"), new StageOutput(BigDecimal.ZERO, BigDecimal.ZERO, null, true, null,
                        null, null, in.date(), "Close curing stage"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("posting", res.get("posting"));
        out.put("batch", batch == null ? null : batch(str(batch, "id")));
        out.put("order", order(orderId));
        return out;
    }

    // ================================================================== curing batches

    /** Effective status: a reversed pressing cancels the batch, a reversed cubing puts it back into curing. */
    private static final String BATCH_SELECT = "SELECT b.*, CASE WHEN pp.\"reversedAt\" IS NOT NULL THEN 'cancelled'"
            + " WHEN b.\"cubingPostingId\" IS NOT NULL AND cp.\"reversedAt\" IS NOT NULL THEN 'curing'"
            + " ELSE b.status END AS \"effectiveStatus\", o.\"orderCode\", o.status AS \"orderStatus\","
            + " p.name AS \"productName\", p.sku AS \"productSku\", a.code AS \"curingAreaCode\","
            + " a.name AS \"curingAreaName\", st.\"qcStatus\", st.\"qcReference\", st.\"qcPassDueAt\","
            + " st.\"qcCheckpointCode\", m.\"targetMaturity\", m.\"datumTemperatureC\""
            + " FROM \"PrecastCuringBatch\" b JOIN \"ProductionOrder\" o ON o.id = b.\"orderId\""
            + " JOIN \"ProductionStagePosting\" pp ON pp.id = b.\"pressingPostingId\""
            + " LEFT JOIN \"ProductionStagePosting\" cp ON cp.id = b.\"cubingPostingId\""
            + " JOIN \"ProductionOrderStage\" st ON st.id = b.\"stageId\""
            + " LEFT JOIN \"PrecastMixDesign\" m ON m.\"bomId\" = o.\"bomId\" AND m.\"organizationId\" = b.\"organizationId\""
            + " LEFT JOIN \"Product\" p ON p.id = b.\"productId\" AND p.\"organizationId\" = b.\"organizationId\""
            + " LEFT JOIN \"PrecastCuringArea\" a ON a.id = b.\"curingAreaId\""
            + " WHERE b.\"organizationId\" = ?";

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> batches(String status, String curingAreaId, String orderId, Boolean ready,
            String search, Integer page, Integer pageSize) {
        int pg = PrecastSetupService.page(page);
        int size = PrecastSetupService.size(pageSize);
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (has(curingAreaId)) {
            where.append(" AND b.\"curingAreaId\" = ?");
            params.add(curingAreaId);
        }
        if (has(orderId)) {
            where.append(" AND b.\"orderId\" = ?");
            params.add(orderId);
        }
        if (has(search)) {
            where.append(" AND (b.code ILIKE ? OR b.\"batchNumber\" ILIKE ? OR o.\"orderCode\" ILIKE ?"
                    + " OR p.name ILIKE ? OR b.\"palletLocation\" ILIKE ?)");
            String like = "%" + search.trim() + "%";
            params.addAll(List.of(like, like, like, like, like));
        }
        String outer = "";
        List<Object> outerParams = new ArrayList<>();
        if (has(status)) {
            outer += " AND x.\"effectiveStatus\" = ?";
            outerParams.add(status);
        }
        if (ready != null) {
            outer += ready ? " AND x.\"effectiveStatus\" = 'curing' AND x.\"curingUntil\" <= ?"
                    : " AND NOT (x.\"effectiveStatus\" = 'curing' AND x.\"curingUntil\" <= ?)";
            outerParams.add(MfgDb.param(Instant.now()));
        }
        List<Object> all = new ArrayList<>(List.of(MfgDb.org()));
        all.addAll(params);
        all.addAll(outerParams);
        long total = db.jdbc().sql("SELECT count(*) FROM (" + BATCH_SELECT + where + ") x WHERE true" + outer)
                .params(all).query(Long.class).single();
        all.add(size);
        all.add((pg - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT * FROM (" + BATCH_SELECT + where
                + ") x WHERE true" + outer + " ORDER BY x.\"curingUntil\", x.code LIMIT ? OFFSET ?").params(all));
        rows.forEach(this::decorate);
        return PageResult.of(rows, pg, size, total);
    }

    private List<Map<String, Object>> batchRows(String where, List<Object> params, String order, Integer limit,
            Integer offset) {
        List<Object> all = new ArrayList<>(List.of(MfgDb.org()));
        all.addAll(params);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(BATCH_SELECT + where + order).params(all));
        rows.forEach(this::decorate);
        return rows;
    }

    /** Adds status (effective), days cured, ready flag and maturity progress. */
    private void decorate(Map<String, Object> b) {
        b.put("status", b.remove("effectiveStatus"));
        Instant now = Instant.now();
        Instant start = (Instant) b.get("startedAt");
        Instant until = (Instant) b.get("curingUntil");
        b.put("daysCured", CuringHold.daysCured(start, now));
        b.put("ready", "curing".equals(b.get("status")) && !now.isBefore(until));
        BigDecimal target = b.get("targetMaturity") == null ? null : dec(b, "targetMaturity");
        b.put("maturityPct", target == null || target.signum() == 0 ? null
                : dec(b, "maturity").multiply(HUNDRED).divide(target, 1, java.math.RoundingMode.HALF_UP));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> batch(String id) {
        List<Map<String, Object>> rows = batchRows(" AND b.id = ?", List.of(id), "", null, null);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Curing batch not found");
        }
        Map<String, Object> b = rows.getFirst();
        b.put("readings", SqlRows.list(db.jdbc().sql("SELECT r.*, a.code AS \"curingAreaCode\" FROM"
                + " \"PrecastCuringReading\" r LEFT JOIN \"PrecastCuringArea\" a ON a.id = r.\"curingAreaId\""
                + " WHERE r.\"organizationId\" = ? AND r.\"batchId\" = ? ORDER BY r.\"readAt\" DESC")
                .params(MfgDb.org(), id)));
        b.put("qualityLots", SqlRows.list(db.jdbc().sql("SELECT id, \"lotNumber\", stage, status, result,"
                + " quantity, \"createdAt\" FROM \"QualityInspectionLot\" WHERE \"organizationId\" = ? AND"
                + " ((\"sourceType\" = 'production_order' AND \"sourceId\" = ? AND \"sourceLineId\" = ?)"
                + " OR (\"sourceType\" = ? AND \"sourceId\" = ?)) ORDER BY \"createdAt\"").params(MfgDb.org(),
                b.get("orderId"), b.get("stageId"), FINAL_SOURCE, id)));
        return b;
    }

    private Map<String, Object> lockBatch(String id) {
        Map<String, Object> pre = db.byId(PrecastCuringBatch.class, id);
        if (pre == null) {
            throw ApiException.notFound("Curing batch not found");
        }
        db.jdbc().sql("SELECT id FROM \"PrecastCuringBatch\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, MfgDb.org()).query(String.class).list();
        return batch(id);
    }

    /** Temperature (maturity) reading. */
    @Transactional
    public Map<String, Object> reading(String id, CuringReading in) {
        Map<String, Object> b = lockBatch(id);
        if (!"curing".equals(b.get("status"))) {
            throw ApiException.badRequest("Batch " + b.get("code") + " is " + b.get("status") + "; it is no longer curing.");
        }
        Instant at = has(in.readAt()) ? EntityPatch.toInstant(in.readAt()) : Instant.now();
        if (at.isBefore((Instant) b.get("startedAt"))) {
            throw ApiException.badRequest("A reading cannot be earlier than the pressing of batch " + b.get("code")
                    + ".");
        }
        db.insert(PrecastCuringReading.class, Obj.of("batchId", id, "kind", "temperature", "readAt", at,
                "temperatureC", in.temperatureC(), "humidityPct", in.humidityPct(), "notes",
                PrecastSetupService.trim(in.notes()), "createdBy", TenantContext.userId()));
        List<MaturityCalculator.Reading> readings = new ArrayList<>();
        Instant last = null;
        BigDecimal lastT = null;
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT \"readAt\", \"temperatureC\" FROM"
                + " \"PrecastCuringReading\" WHERE \"organizationId\" = ? AND \"batchId\" = ? AND kind = 'temperature'"
                + " ORDER BY \"readAt\"").params(MfgDb.org(), id))) {
            Instant ra = (Instant) r.get("readAt");
            readings.add(new MaturityCalculator.Reading(ra, dec(r, "temperatureC")));
            last = ra;
            lastT = dec(r, "temperatureC");
        }
        BigDecimal datum = b.get("datumTemperatureC") == null ? null : dec(b, "datumTemperatureC");
        BigDecimal maturity = MaturityCalculator.maturity((Instant) b.get("startedAt"), datum, readings);
        db.update(PrecastCuringBatch.class, id, Obj.of("maturity", maturity, "lastTemperatureC", lastT,
                "lastReadingAt", last));
        return batch(id);
    }

    /** Move the batch's pallets to another curing area and / or bay. */
    @Transactional
    public Map<String, Object> move(String id, MovePallets in) {
        Map<String, Object> b = lockBatch(id);
        if (!"curing".equals(b.get("status")) && !"cubed".equals(b.get("status"))) {
            throw ApiException.badRequest("Batch " + b.get("code") + " is " + b.get("status") + ".");
        }
        if (in.curingAreaId() == null && in.palletLocation() == null) {
            throw ApiException.badRequest("Give a curing area or a pallet location.");
        }
        if (in.curingAreaId() != null) {
            setup.requireArea(in.curingAreaId());
        }
        String area = in.curingAreaId() != null ? in.curingAreaId() : str(b, "curingAreaId");
        String loc = in.palletLocation() != null ? PrecastSetupService.trim(in.palletLocation())
                : str(b, "palletLocation");
        db.insert(PrecastCuringReading.class, Obj.of("batchId", id, "kind", "move", "readAt", Instant.now(),
                "curingAreaId", area, "palletLocation", loc, "notes", PrecastSetupService.trim(in.notes()),
                "createdBy", TenantContext.userId()));
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("curingAreaId", area);
        upd.put("palletLocation", loc);
        db.update(PrecastCuringBatch.class, id, upd);
        audit.recordInTx(AuditInput.of("PrecastCuringBatch", id, "update", null, Obj.of("code", b.get("code"),
                "curingAreaId", area, "palletLocation", loc)));
        return batch(id);
    }

    /**
     * Cube a cured batch: good + second grade + broken must account for every block; refused before the minimum
     * curing time. Posts the curing stage output (good → QC gate, second grade → NRV by-product, broken → scrap) and
     * completes the stage when pressing is complete and no other batch of the order is still curing.
     */
    @Transactional
    public Map<String, Object> cube(String id, CubeBatch in) {
        Map<String, Object> b = lockBatch(id);
        if (!"curing".equals(b.get("status"))) {
            throw ApiException.badRequest("Batch " + b.get("code") + " is " + b.get("status") + "; only a curing batch"
                    + " can be cubed.");
        }
        Instant at = has(in.date()) ? EntityPatch.toInstant(in.date()) : Instant.now();
        Instant until = (Instant) b.get("curingUntil");
        if (at.isBefore(until)) {
            throw ApiException.badRequest("Batch " + b.get("code") + " cures until " + until + " (minimum "
                    + b.get("minCuringDays") + " day(s)).");
        }
        if (at.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("The cubing date cannot be in the future.");
        }
        BigDecimal good = in.qtyGood();
        BigDecimal second = in.qtySecondGrade() == null ? BigDecimal.ZERO : in.qtySecondGrade();
        BigDecimal broken = in.qtyBroken() == null ? BigDecimal.ZERO : in.qtyBroken();
        BigDecimal qty = dec(b, "quantity");
        if (good.add(second).add(broken).compareTo(qty) != 0) {
            throw ApiException.badRequest("Account for all " + qty.stripTrailingZeros().toPlainString()
                    + " blocks of batch " + b.get("code") + ": good + second grade + broken = "
                    + good.add(second).add(broken).stripTrailingZeros().toPlainString() + ".");
        }
        String orderId = str(b, "orderId");
        List<Map<String, Object>> st = stages.stages(orderId);
        Map<String, Object> cure = st.getLast();
        if (!str(cure, "id").equals(str(b, "stageId"))) {
            throw ApiException.badRequest("The curing stage of order " + b.get("orderCode") + " changed.");
        }
        List<OutputQty> outputs = null;
        if (second.signum() > 0) {
            String secondOutput = secondGradeOutput(orderId, str(cure, "routingOperationId"), str(b, "productId"));
            if (secondOutput == null) {
                throw ApiException.badRequest("Set a second-grade product on the block specification first.");
            }
            outputs = List.of(new OutputQty(secondOutput, second, str(b, "batchNumber")));
        }
        Map<String, Object> press = st.get(st.size() - 2);
        long otherCuring = db.jdbc().sql("SELECT count(*) FROM \"PrecastCuringBatch\" b JOIN \"ProductionStagePosting\""
                + " pp ON pp.id = b.\"pressingPostingId\" WHERE b.\"organizationId\" = ? AND b.\"orderId\" = ?"
                + " AND b.id <> ? AND b.status = 'curing' AND pp.\"reversedAt\" IS NULL").params(MfgDb.org(), orderId,
                id).query(Long.class).single();
        boolean completes = "completed".equals(press.get("status")) && otherCuring == 0;
        String dateIso = has(in.date()) ? in.date() : null;
        Map<String, Object> res = stages.output(str(cure, "id"), new StageOutput(good, broken, in.scrapReasonCode(),
                completes, outputs, str(b, "batchNumber"), null, dateIso, in.notes() != null ? in.notes()
                : "Cubing " + b.get("code")));
        @SuppressWarnings("unchecked")
        String postingId = str((Map<String, Object>) res.get("posting"), "id");
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("cubingPostingId", postingId);
        upd.put("status", good.signum() > 0 ? "cubed" : "released");
        upd.put("qtyGood", good);
        upd.put("qtySecondGrade", second);
        upd.put("qtyBroken", broken);
        upd.put("scrapReasonCode", in.scrapReasonCode());
        upd.put("cubedAt", at);
        upd.put("releasedAt", good.signum() > 0 ? null : at);
        db.update(PrecastCuringBatch.class, id, upd);
        audit.recordInTx(AuditInput.of("PrecastCuringBatch", id, "update", null, Obj.of("code", b.get("code"),
                "action", "cube", "good", good, "secondGrade", second, "broken", broken)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("posting", res.get("posting"));
        out.put("batch", batch(id));
        return out;
    }

    /** Manual strength gate decision of the batch's curing stage (when no quality lot decides it). */
    @Transactional
    public Map<String, Object> gateDecision(String id, GateDecision in) {
        Map<String, Object> b = lockBatch(id);
        if (!"cubed".equals(b.get("status")) && !"failed".equals(b.get("status"))) {
            throw ApiException.badRequest("Batch " + b.get("code") + " is " + b.get("status")
                    + "; only a cubed batch waits for its strength gate.");
        }
        stages.recordResult(str(b, "stageId"), new CheckpointDecision("passed".equals(in.result()),
                has(in.reference()) ? in.reference().trim() : str(b, "code"), in.notes()));
        return batch(id);
    }

    /** Batches of a gated stage follow its decision ({@link StageGateListener}). */
    @Override
    public void gateDecided(String stageId, String qcStatus) {
        if ("passed".equals(qcStatus)) {
            db.jdbc().sql("UPDATE \"PrecastCuringBatch\" SET status = 'released', \"releasedAt\" = " + MfgDb.NOW
                    + ", \"updatedAt\" = " + MfgDb.NOW + " WHERE \"organizationId\" = ? AND \"stageId\" = ?"
                    + " AND status IN ('cubed', 'failed')").params(MfgDb.org(), stageId).update();
        } else if ("failed".equals(qcStatus)) {
            db.jdbc().sql("UPDATE \"PrecastCuringBatch\" SET status = 'failed', \"updatedAt\" = " + MfgDb.NOW
                    + " WHERE \"organizationId\" = ? AND \"stageId\" = ? AND status = 'cubed'")
                    .params(MfgDb.org(), stageId).update();
        }
    }

    /** Opens the final (e.g. 28-day) inspection lot of a released batch; 400 when no active final plan applies. */
    @Transactional
    public Map<String, Object> finalInspection(String id) {
        Map<String, Object> b = lockBatch(id);
        if (!"released".equals(b.get("status")) || dec(b, "qtyGood").signum() <= 0) {
            throw ApiException.badRequest("Batch " + b.get("code") + " has no released blocks to inspect.");
        }
        if (b.get("finalLotId") != null) {
            return batch(id);
        }
        Map<String, Object> lot = openFinalLot(b);
        if (lot == null) {
            throw ApiException.badRequest("No active final inspection plan (with an active specification) for "
                    + b.get("productName") + ".");
        }
        return batch(id);
    }

    private Map<String, Object> openFinalLot(Map<String, Object> b) {
        Map<String, Object> order = db.byId(com.inventoryerp.manufacturing.domain.ProductionOrder.class,
                str(b, "orderId"));
        Map<String, Object> lot = lots.openLotIfPlanned(new OpenLot("final", str(b, "productId"), null,
                str(b, "batchNumber"), dec(b, "qtyGood"), null, str(order, "outputLocationId"), FINAL_SOURCE,
                str(b, "id"), null, str(b, "code"), null, null, null, null, "quality", true,
                "Final inspection of precast batch " + b.get("code")));
        if (lot != null) {
            db.update(PrecastCuringBatch.class, str(b, "id"), Obj.of("finalLotId", lot.get("id")));
        }
        return lot;
    }

    /** Pallet labels with a QR code (batch, pallet n / N, product, quantity). */
    @Transactional(readOnly = true)
    public Map<String, Object> labels(String id) {
        Map<String, Object> b = batch(id);
        Map<String, Object> spec = setup.specFor(str(b, "productId"));
        BigDecimal qty = "curing".equals(b.get("status")) || "cancelled".equals(b.get("status")) ? dec(b, "quantity")
                : dec(b, "qtyGood");
        int perPallet = spec == null ? qty.intValue() : ((Number) spec.get("unitsPerPallet")).intValue();
        int pallets = (int) Math.max(1, BatchScaler.ceil(qty, Math.max(1, perPallet)));
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal left = qty;
        for (int i = 1; i <= pallets; i++) {
            BigDecimal onPallet = left.min(BigDecimal.valueOf(Math.max(1, perPallet)));
            left = left.subtract(onPallet);
            String palletCode = b.get("batchNumber") + "-P" + String.format("%02d", i);
            String payload = "PRECAST|" + b.get("batchNumber") + "|" + i + "/" + pallets + "|" + b.get("productSku")
                    + "|" + onPallet.stripTrailingZeros().toPlainString();
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("palletNo", i);
            l.put("pallets", pallets);
            l.put("palletCode", palletCode);
            l.put("quantity", onPallet);
            l.put("payload", payload);
            l.put("qrSvg", QrSvg.render(payload));
            out.add(l);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("batch", b);
        res.put("spec", spec);
        res.put("labels", out);
        return res;
    }

    // ================================================================== job

    /**
     * One organization's curing tick: passes the gates whose curing hold has ended (a pass recorded while curing)
     * and opens final inspection lots for batches released in the last two days. Each step in its own transaction.
     */
    public Map<String, Object> tick() {
        int passed = 0;
        int finalLots = 0;
        List<String> due = db.jdbc().sql("SELECT id FROM \"ProductionOrderStage\" WHERE \"organizationId\" = ?"
                + " AND \"qcStatus\" = 'curing' AND \"qcPassDueAt\" <= ?").params(MfgDb.org(),
                MfgDb.param(Instant.now())).query(String.class).list();
        for (String stageId : due) {
            try {
                Map<String, Object> s = db.byId(ProductionOrderStage.class, stageId);
                stages.recordResult(stageId, new CheckpointDecision(true, str(s, "qcReference"),
                        "Minimum curing time reached"));
                passed++;
            } catch (ApiException e) {
                db.update(ProductionOrderStage.class, stageId, Obj.of("qcStatus", "pending", "qcPassDueAt", null));
            }
        }
        List<String> released = db.jdbc().sql("SELECT id FROM \"PrecastCuringBatch\" WHERE \"organizationId\" = ?"
                + " AND status = 'released' AND \"finalLotId\" IS NULL AND \"qtyGood\" > 0 AND \"releasedAt\" >= ?")
                .params(MfgDb.org(), MfgDb.param(Instant.now().minus(Duration.ofDays(2)))).query(String.class).list();
        for (String id : released) {
            Boolean opened = tx.execute(s -> openFinalLot(batch(id)) != null);
            if (Boolean.TRUE.equals(opened)) {
                finalLots++;
            }
        }
        return Obj.of("gatesPassed", passed, "finalLotsOpened", finalLots);
    }

    // ================================================================== helpers

    private String secondGradeOutput(String orderId, String operationId, String productId) {
        Map<String, Object> spec = setup.specFor(productId);
        String second = spec == null ? null : str(spec, "secondGradeProductId");
        if (second == null) {
            return null;
        }
        return db.jdbc().sql("SELECT id FROM \"ProductionOutput\" WHERE \"orderId\" = ? AND \"productId\" = ?"
                + " AND \"operationId\" = ? LIMIT 1").params(orderId, second, operationId).query(String.class)
                .optional().orElse(null);
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }
}
