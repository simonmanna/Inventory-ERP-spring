package com.inventoryerp.quality.application;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.inventory.application.VirtualLocationService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quality.application.QualityCommands.LotInput;
import com.inventoryerp.quality.application.QualityCommands.LotQuery;
import com.inventoryerp.quality.application.QualityCommands.OpenLot;
import com.inventoryerp.quality.application.QualityCommands.RecordResults;
import com.inventoryerp.quality.application.QualityCommands.ResultEntry;
import com.inventoryerp.quality.application.QualityCommands.SieveReadingInput;
import com.inventoryerp.quality.application.SpecEvaluator.Evaluation;
import com.inventoryerp.quality.application.SpecEvaluator.Parameter;
import com.inventoryerp.quality.application.SpecEvaluator.Sample;
import com.inventoryerp.quality.application.SpecEvaluator.SieveReading;
import com.inventoryerp.quality.domain.QualityInspectionLot;
import com.inventoryerp.quality.domain.QualityLotDecision;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Inspection lots (ADR-S013 §3): open (manual, GRN subscriber, 2.1 / 2.3 / 2.4 through {@link #openLot}), sample size
 * from the plan, hold policy on open, per-sample results with evaluation against the lot's specification version, and
 * the auto-NCR on failure. Stock decisions live in {@link QualityDecisionService}.
 */
@Service
public class QualityLotService {

    static final String AUDIT_ENTITY = "QualityInspectionLot";
    static final String STOCK_SOURCE = "quality_lot";

    private final QualityDb db;
    private final QualityPlanService plans;
    private final QualitySpecService specs;
    private final QualityNcrService ncrs;
    private final StockService stock;
    private final VirtualLocationService locations;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public QualityLotService(QualityDb db, QualityPlanService plans, QualitySpecService specs, QualityNcrService ncrs,
            StockService stock, VirtualLocationService locations, SequenceService sequence, AuditService audit,
            EventBus events) {
        this.db = db;
        this.plans = plans;
        this.specs = specs;
        this.ncrs = ncrs;
        this.stock = stock;
        this.locations = locations;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ open

    /** Manual lot from the screen (source {@code manual}). */
    @Transactional
    public Map<String, Object> create(LotInput in) {
        return openLot(new OpenLot(in.stage(), in.productId(), in.variantId(), in.batchNumber(), in.quantity(), null,
                in.locationId(), "manual", null, null, in.sourceRef(), in.stageKey(), in.supplierPartnerId(),
                in.workCenterId(), in.planId(), "quality", in.applyHoldPolicy(), in.notes()));
    }

    /**
     * Open a lot (public API). Idempotent per source / line / stage key: a live lot for the same source is returned
     * unchanged. Needs an active plan (given or matched) whose specification has an active version.
     */
    @Transactional
    public Map<String, Object> openLot(OpenLot in) {
        if (!List.of("incoming", "in_process", "final").contains(in.stage())) {
            throw ApiException.badRequest("Unknown inspection stage " + in.stage());
        }
        if (in.quantity() == null || in.quantity().signum() <= 0) {
            throw ApiException.badRequest("Lot quantity must be positive.");
        }
        if (!db.exists("Product", in.productId(), true)) {
            throw ApiException.notFound("Product " + in.productId() + " not found");
        }
        if (Q.has(in.locationId()) && !db.exists("InventoryLocation", in.locationId(), true)) {
            throw ApiException.notFound("Location not found");
        }
        if (Q.has(in.workCenterId()) && !db.exists("WorkCenter", in.workCenterId(), true)) {
            throw ApiException.notFound("Work centre not found");
        }
        String sourceType = Q.has(in.sourceType()) ? in.sourceType() : "manual";
        if (in.sourceId() != null) {
            db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("qlot:" + QualityDb.org() + ":"
                    + sourceType + ":" + in.sourceId() + ":" + Q.or(in.sourceLineId(), "") + ":"
                    + Q.or(in.stageKey(), "")).query().listOfRows();
            String existing = existingLot(sourceType, in.sourceId(), in.sourceLineId(), in.stageKey());
            if (existing != null) {
                return get(existing);
            }
        }
        Map<String, Object> plan;
        if (Q.has(in.planId())) {
            plan = plans.requirePlan(in.planId());
            if (!in.stage().equals(plan.get("stage")) || !in.productId().equals(plan.get("productId"))) {
                throw ApiException.badRequest("Inspection plan " + plan.get("code") + " is for another stage or product.");
            }
        } else {
            plan = plans.findPlan(in.stage(), in.productId(), in.supplierPartnerId(), in.stageKey());
        }
        if (plan == null) {
            throw ApiException.badRequest("No active " + in.stage().replace('_', '-') + " inspection plan for this product.");
        }
        String specId = specs.activeVersionOf(Q.str(plan, "specificationId"));
        if (specId == null) {
            throw ApiException.badRequest("The specification of plan " + plan.get("code") + " has no active version.");
        }
        String stockControl = Q.has(in.stockControl()) ? in.stockControl() : "quality";
        if (!List.of("quality", "source").contains(stockControl)) {
            throw ApiException.badRequest("Unknown stock control " + stockControl);
        }
        int sampleSize = SamplingCalculator.sampleSize(QualityPlanService.sampling(plan), in.quantity());
        String lotNumber = sequence.next("quality_lot", "QL-", 6);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lotNumber", lotNumber);
        data.put("stage", in.stage());
        data.put("planId", plan.get("id"));
        data.put("specificationId", specId);
        data.put("productId", in.productId());
        data.put("variantId", Q.trim(in.variantId()));
        data.put("batchNumber", Q.trim(in.batchNumber()));
        data.put("quantity", in.quantity());
        data.put("uomId", in.uomId());
        data.put("locationId", Q.trim(in.locationId()));
        data.put("stockControl", stockControl);
        data.put("sourceType", sourceType);
        data.put("sourceId", in.sourceId());
        data.put("sourceLineId", in.sourceLineId());
        data.put("sourceRef", Q.trim(in.sourceRef()));
        data.put("stageKey", Q.trim(in.stageKey()));
        data.put("supplierPartnerId", Q.trim(in.supplierPartnerId()));
        data.put("workCenterId", Q.trim(in.workCenterId()) != null ? in.workCenterId().trim()
                : "production_order".equals(sourceType) ? orderWorkCenter(in.sourceId()) : null);
        data.put("sampleSize", sampleSize);
        data.put("status", "open");
        data.put("result", "pending");
        data.put("independentRelease", !Boolean.FALSE.equals(plan.get("independentRelease")));
        data.put("autoNcr", !Boolean.FALSE.equals(plan.get("autoNcr")));
        data.put("notes", Q.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> lot = db.insert(QualityInspectionLot.class, data);
        String id = Q.str(lot, "id");
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, Obj.of("lotNumber", lotNumber, "stage",
                in.stage(), "quantity", JsNumbers.dec(in.quantity()), "sourceType", sourceType, "sourceId",
                in.sourceId())));
        if (!Boolean.FALSE.equals(in.applyHoldPolicy())) {
            String policy = Q.str(plan, "holdPolicy");
            boolean movable = "quality".equals(stockControl) && Q.has(Q.str(lot, "locationId"));
            if ("quarantine".equals(policy) && movable) {
                quarantineStock(lot, Q.dec(lot, "quantity"), "Hold policy of plan " + plan.get("code"));
            } else if ("quarantine".equals(policy) || "hold".equals(policy)) {
                db.update(QualityInspectionLot.class, id, Obj.of("status", "hold"));
                decision(id, "hold", null, null, null, "Hold policy of plan " + plan.get("code"), null, null);
            }
        }
        events.publish(DomainEvents.QUALITY_LOT_CREATED, Obj.of("organizationId", QualityDb.org(), "lotId", id,
                "lotNumber", lotNumber, "stage", in.stage(), "productId", in.productId(), "sourceType", sourceType,
                "sourceId", in.sourceId(), "stageKey", in.stageKey()));
        return get(id);
    }

    /**
     * {@link #openLot} for callers that must not fail (e.g. the manufacturing stage-gate hook, which runs inside the
     * stage posting): returns null instead of throwing when no active plan with an active specification applies, so
     * the caller falls back to its manual decision.
     */
    @Transactional
    public Map<String, Object> openLotIfPlanned(OpenLot in) {
        Map<String, Object> plan = Q.has(in.planId()) ? null
                : plans.findPlan(in.stage(), in.productId(), in.supplierPartnerId(), in.stageKey());
        if (!Q.has(in.planId()) && (plan == null || specs.activeVersionOf(Q.str(plan, "specificationId")) == null)) {
            return null;
        }
        return openLot(in);
    }

    /** Move {@code qty} of the lot from its location into QC quarantine and mark it quarantined. */
    void quarantineStock(Map<String, Object> lot, BigDecimal qty, String reason) {
        String quarantine = locations.ensure(VirtualLocationService.QC_QUARANTINE, "QC Quarantine");
        String from = Q.str(lot, "locationId");
        String ledger = null;
        if (!quarantine.equals(from)) {
            ledger = stock.transfer(StockMove.transfer(Q.str(lot, "productId"), from, quarantine, qty)
                    .variant(Q.str(lot, "variantId")).batch(Q.str(lot, "batchNumber"))
                    .source(STOCK_SOURCE, Q.str(lot, "lotNumber"))
                    .notes("Quarantine " + lot.get("lotNumber"))).ledgerCode();
        }
        db.update(QualityInspectionLot.class, Q.str(lot, "id"), Obj.of("status", "quarantined",
                "quarantineLocationId", quarantine, "updatedBy", TenantContext.userId()));
        decision(Q.str(lot, "id"), "quarantine", qty, from, quarantine, reason, ledger, null);
    }

    /** Insert a decision row; returns its id. */
    String decision(String lotId, String decision, BigDecimal qty, String from, String to, String reason,
            String ledgerCodes, Map<String, Object> extra) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lotId", lotId);
        d.put("decision", decision);
        d.put("quantity", qty);
        d.put("fromLocationId", from);
        d.put("toLocationId", to);
        d.put("reason", Q.trim(reason));
        d.put("ledgerCodes", ledgerCodes);
        d.put("decidedBy", TenantContext.userId());
        d.put("decidedAt", Instant.now());
        if (extra != null) {
            d.putAll(extra);
        }
        return Q.str(db.insert(QualityLotDecision.class, d), "id");
    }

    private String existingLot(String sourceType, String sourceId, String sourceLineId, String stageKey) {
        return db.jdbc().sql("SELECT id FROM \"QualityInspectionLot\" WHERE \"organizationId\" = ? AND \"sourceType\" = ?"
                + " AND \"sourceId\" = ? AND COALESCE(\"sourceLineId\", '') = ? AND COALESCE(\"stageKey\", '') = ?"
                + " AND status <> 'cancelled' LIMIT 1").params(QualityDb.org(), sourceType, sourceId,
                Q.or(sourceLineId, ""), Q.or(stageKey, "")).query(String.class).optional().orElse(null);
    }

    /** First work centre of a production order's work orders (read-only, for line reports). */
    private String orderWorkCenter(String orderId) {
        if (orderId == null) {
            return null;
        }
        return db.jdbc().sql("SELECT \"workCenterId\" FROM \"WorkOrder\" WHERE \"productionOrderId\" = ? AND"
                + " \"organizationId\" = ? AND \"workCenterId\" IS NOT NULL ORDER BY \"operationSequence\" NULLS LAST,"
                + " \"createdAt\" LIMIT 1").params(orderId, QualityDb.org()).query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ results

    @Transactional
    public Map<String, Object> recordResults(String lotId, RecordResults in) {
        recordResults(lotId, in.results(), "manual");
        if (Q.has(in.notes())) {
            db.update(QualityInspectionLot.class, lotId, Obj.of("notes", in.notes().trim()));
        }
        return get(lotId);
    }

    /**
     * Upsert results (manual entry or a lab adapter) and re-evaluate the lot; returns the evaluation. Results can no
     * longer change once the lot is closed or cancelled.
     */
    Evaluation recordResults(String lotId, List<ResultEntry> entries, String source) {
        Map<String, Object> lot = lockLot(lotId);
        if (List.of("closed", "cancelled").contains(Q.str(lot, "status"))) {
            throw ApiException.badRequest("Lot " + lot.get("lotNumber") + " is " + lot.get("status")
                    + "; results can no longer change.");
        }
        List<Parameter> params = specs.parameters(Q.str(lot, "specificationId"));
        Map<String, Parameter> byId = new HashMap<>();
        Map<String, Parameter> byCode = new HashMap<>();
        params.forEach(p -> {
            byId.put(p.id(), p);
            byCode.put(p.code(), p);
        });
        int sampleSize = (Integer) lot.get("sampleSize");
        for (ResultEntry e : entries) {
            Parameter p = e.parameterId() != null ? byId.get(e.parameterId())
                    : e.parameterCode() != null ? byCode.get(e.parameterCode().trim().toUpperCase(Locale.ROOT)) : null;
            if (p == null) {
                throw ApiException.badRequest("Unknown parameter " + Q.or(e.parameterId(), e.parameterCode())
                        + " for lot " + lot.get("lotNumber") + ".");
            }
            int sampleNo = e.sampleNo() == null ? 1 : e.sampleNo();
            if (sampleNo > sampleSize) {
                throw ApiException.badRequest("Sample " + sampleNo + " exceeds the sample size of lot "
                        + lot.get("lotNumber") + " (" + sampleSize + ").");
            }
            List<SieveReading> sieve = new ArrayList<>();
            for (SieveReadingInput r : e.sieve() == null ? List.<SieveReadingInput>of() : e.sieve()) {
                sieve.add(new SieveReading(r.sieveMm(), r.passingPct()));
            }
            switch (p.kind()) {
                case "numeric" -> {
                    if (e.numericValue() == null) {
                        throw ApiException.badRequest("Parameter " + p.code() + " needs a numeric value.");
                    }
                }
                case "sieve" -> {
                    String problem = SpecEvaluator.validateSieve(sieve);
                    if (problem != null) {
                        throw ApiException.badRequest("Parameter " + p.code() + ": " + problem);
                    }
                }
                default -> {
                    if (!Q.has(e.textValue()) && e.numericValue() == null) {
                        throw ApiException.badRequest("Parameter " + p.code() + " needs a value.");
                    }
                }
            }
            Sample sample = new Sample(sampleNo, e.numericValue(), Q.trim(e.textValue()), sieve);
            boolean passed = SpecEvaluator.check(p, sample).isEmpty();
            List<Map<String, Object>> sieveJson = new ArrayList<>();
            sieve.forEach(r -> sieveJson.add(Obj.of("sieveMm", r.sieveMm(), "passingPct", r.passingPct())));
            db.jdbc().sql("INSERT INTO \"QualityLotResult\" (id, \"organizationId\", \"lotId\", \"parameterId\","
                    + " \"sampleNo\", \"numericValue\", \"textValue\", \"sieveResults\", passed, source, \"labReference\","
                    + " \"testedAt\", \"recordedBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?,"
                    + " ?, ?, ?, ?, " + QualityDb.NOW + ", " + QualityDb.NOW + ") ON CONFLICT (\"lotId\", \"parameterId\","
                    + " \"sampleNo\") DO UPDATE SET \"numericValue\" = EXCLUDED.\"numericValue\", \"textValue\" ="
                    + " EXCLUDED.\"textValue\", \"sieveResults\" = EXCLUDED.\"sieveResults\", passed = EXCLUDED.passed,"
                    + " source = EXCLUDED.source, \"labReference\" = EXCLUDED.\"labReference\", \"testedAt\" ="
                    + " EXCLUDED.\"testedAt\", \"recordedBy\" = EXCLUDED.\"recordedBy\", \"updatedAt\" = " + QualityDb.NOW)
                    .params(UUID.randomUUID().toString(), QualityDb.org(), lotId, p.id(), sampleNo, e.numericValue(),
                            Q.trim(e.textValue()), EntityJson.toJson(sieveJson), passed, source,
                            Q.trim(e.labReference()), e.testedAt() == null ? null : SqlRows.ts(e.testedAt()),
                            TenantContext.userId())
                    .update();
        }
        Evaluation ev = evaluate(lot, params);
        String before = Q.str(lot, "result");
        db.update(QualityInspectionLot.class, lotId, Obj.of("result", ev.result(), "evaluation",
                EntityJson.toJson(ev.toMap()), "inspectedBy", TenantContext.userId(), "inspectedAt", Instant.now(),
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, lotId, "measure", Obj.of("result", before),
                Obj.of("result", ev.result(), "entries", entries.size(), "source", source)));
        if (!ev.result().equals(before)) {
            events.publish(DomainEvents.QUALITY_LOT_EVALUATED, Obj.of("organizationId", QualityDb.org(), "lotId",
                    lotId, "lotNumber", lot.get("lotNumber"), "result", ev.result(), "sourceType", lot.get("sourceType"),
                    "sourceId", lot.get("sourceId"), "stageKey", lot.get("stageKey")));
            if ("fail".equals(ev.result()) && !Boolean.FALSE.equals(lot.get("autoNcr"))) {
                ncrs.raiseForFailedLot(lot, ev);
            }
        }
        return ev;
    }

    /** Evaluation of the lot's stored results against its specification version. */
    Evaluation evaluate(Map<String, Object> lot, List<Parameter> params) {
        Map<String, List<Sample>> samples = new HashMap<>();
        for (Map<String, Object> r : resultRows(Q.str(lot, "id"))) {
            samples.computeIfAbsent(Q.str(r, "parameterId"), k -> new ArrayList<>()).add(toSample(r));
        }
        return SpecEvaluator.evaluate(params, samples, (Integer) lot.get("sampleSize"));
    }

    static Sample toSample(Map<String, Object> r) {
        List<SieveReading> sieve = new ArrayList<>();
        if (r.get("sieveResults") instanceof JsonNode n && n.isArray()) {
            for (JsonNode x : n) {
                sieve.add(new SieveReading(new BigDecimal(x.get("sieveMm").asString()),
                        new BigDecimal(x.get("passingPct").asString())));
            }
        }
        return new Sample((Integer) r.get("sampleNo"), (BigDecimal) r.get("numericValue"), Q.str(r, "textValue"),
                sieve);
    }

    private List<Map<String, Object>> resultRows(String lotId) {
        return SqlRows.list(db.jdbc().sql("SELECT * FROM \"QualityLotResult\" WHERE \"lotId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"parameterId\", \"sampleNo\"").params(lotId, QualityDb.org()));
    }

    Map<String, Object> lockLot(String id) {
        return db.require(QualityInspectionLot.class, id, true, "Inspection lot not found");
    }

    // ------------------------------------------------------------------ reads

    private static final String SELECT = """
            SELECT l.*, p.name AS "productName", p.sku AS "productSku", COALESCE(u.symbol, u.code) AS "uom",
                   loc.code AS "locationCode", loc.name AS "locationName", pl.code AS "planCode", pl.name AS "planName",
                   s.code AS "specificationCode", s.version AS "specificationVersion", s.name AS "specificationName",
                   pa.name AS "supplierName", wc.code AS "workCenterCode", wc.name AS "workCenterName",
                   (l.quantity - l."releasedQty" - l."rejectedQty" - l."downgradedQty") AS "openQty"
            FROM "QualityInspectionLot" l
            LEFT JOIN "Product" p ON p.id = l."productId" AND p."organizationId" = l."organizationId"
            LEFT JOIN "UnitOfMeasure" u ON u.id = p."uomId"
            LEFT JOIN "InventoryLocation" loc ON loc.id = l."locationId" AND loc."organizationId" = l."organizationId"
            LEFT JOIN "QualityInspectionPlan" pl ON pl.id = l."planId"
            LEFT JOIN "QualitySpecification" s ON s.id = l."specificationId"
            LEFT JOIN "Partner" pa ON pa.id = l."supplierPartnerId" AND pa."organizationId" = l."organizationId"
            LEFT JOIN "WorkCenter" wc ON wc.id = l."workCenterId" AND wc."organizationId" = l."organizationId"
            """;

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(LotQuery q) {
        int page = Q.page(q.page());
        int size = Q.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(QualityDb.org()));
        if (Q.has(q.search())) {
            where.append(" AND (l.\"lotNumber\" ILIKE ? OR l.\"batchNumber\" ILIKE ? OR l.\"sourceRef\" ILIKE ?"
                    + " OR p.name ILIKE ? OR p.sku ILIKE ?)");
            String like = Q.like(q.search());
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }
        Q.eq(where, params, "l.status", q.status());
        Q.eq(where, params, "l.stage", q.stage());
        Q.eq(where, params, "l.result", q.result());
        Q.eq(where, params, "l.\"productId\"", q.productId());
        Q.eq(where, params, "l.\"supplierPartnerId\"", q.supplierPartnerId());
        Q.eq(where, params, "l.\"sourceType\"", q.sourceType());
        Q.eq(where, params, "l.\"sourceId\"", q.sourceId());
        Q.eq(where, params, "l.\"batchNumber\"", q.batchNumber());
        long total = db.jdbc().sql("SELECT count(*) FROM \"QualityInspectionLot\" l LEFT JOIN \"Product\" p ON p.id ="
                + " l.\"productId\" AND p.\"organizationId\" = l.\"organizationId\"" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY l.\"createdAt\" DESC, l.id LIMIT ? OFFSET ?").params(params));
        rows.forEach(r -> r.remove("evaluation"));
        return PageResult.of(rows, page, size, total);
    }

    /** Lot detail: header, specification parameters with their recorded samples, decisions and NCRs. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> lot = SqlRows.one(db.jdbc().sql(SELECT + " WHERE l.id = ? AND l.\"organizationId\" = ?")
                .params(id, QualityDb.org()));
        if (lot == null) {
            throw ApiException.notFound("Inspection lot not found");
        }
        Map<String, List<Map<String, Object>>> byParam = new HashMap<>();
        for (Map<String, Object> r : resultRows(id)) {
            byParam.computeIfAbsent(Q.str(r, "parameterId"), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Map<String, Object> p : specs.parameterRows(Q.str(lot, "specificationId"))) {
            Map<String, Object> row = new LinkedHashMap<>(p);
            row.put("results", byParam.getOrDefault(Q.str(p, "id"), List.of()));
            parameters.add(row);
        }
        lot.put("parameters", parameters);
        lot.put("decisions", SqlRows.list(db.jdbc().sql("SELECT d.*, fl.code AS \"fromLocationCode\", tl.code AS"
                + " \"toLocationCode\", dp.name AS \"downgradeProductName\", TRIM(CONCAT(us.\"firstName\", ' ',"
                + " us.\"lastName\")) AS \"decidedByName\" FROM \"QualityLotDecision\" d LEFT JOIN \"InventoryLocation\" fl"
                + " ON fl.id = d.\"fromLocationId\" LEFT JOIN \"InventoryLocation\" tl ON tl.id = d.\"toLocationId\""
                + " LEFT JOIN \"Product\" dp ON dp.id = d.\"downgradeProductId\" LEFT JOIN \"User\" us ON us.id ="
                + " d.\"decidedBy\" WHERE d.\"lotId\" = ? AND d.\"organizationId\" = ? ORDER BY d.\"decidedAt\", d.id")
                .params(id, QualityDb.org())));
        lot.put("ncrs", SqlRows.list(db.jdbc().sql("SELECT id, \"ncrNumber\", title, severity, status, \"createdAt\""
                + " FROM \"QualityNcr\" WHERE \"lotId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " ORDER BY \"createdAt\"").params(id, QualityDb.org())));
        return lot;
    }

    /** Lots of a product batch (traceability nodes). */
    List<Map<String, Object>> lotsOfBatch(String productId, String batchNumber) {
        return SqlRows.list(db.jdbc().sql("SELECT id, \"lotNumber\", stage, status, result, quantity, \"createdAt\""
                + " FROM \"QualityInspectionLot\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                + " \"batchNumber\" = ? AND status <> 'cancelled' ORDER BY \"createdAt\"")
                .params(QualityDb.org(), productId, batchNumber));
    }
}
