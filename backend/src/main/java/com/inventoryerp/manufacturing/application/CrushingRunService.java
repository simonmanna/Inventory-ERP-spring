package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.CrushingCalculator.Fraction;
import com.inventoryerp.manufacturing.application.CrushingCalculator.StagePlan;
import com.inventoryerp.manufacturing.application.CrushingCommands.RunCostInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.RunInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.RunMeterInput;
import com.inventoryerp.manufacturing.application.CrushingCommands.RunOutputInput;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.application.StageCommands.InitializeStages;
import com.inventoryerp.manufacturing.application.StageCommands.IssueLine;
import com.inventoryerp.manufacturing.application.StageCommands.OutputQty;
import com.inventoryerp.manufacturing.application.StageCommands.StageConversion;
import com.inventoryerp.manufacturing.application.StageCommands.StageIssue;
import com.inventoryerp.manufacturing.application.StageCommands.StageOutput;
import com.inventoryerp.manufacturing.domain.CrushingGradingProfile;
import com.inventoryerp.manufacturing.domain.CrushingPlant;
import com.inventoryerp.manufacturing.domain.CrushingRun;
import com.inventoryerp.manufacturing.domain.CrushingRunCost;
import com.inventoryerp.manufacturing.domain.CrushingRunMeter;
import com.inventoryerp.manufacturing.domain.CrushingRunOutput;
import com.inventoryerp.manufacturing.domain.ProductionMaterial;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionOrderStage;
import com.inventoryerp.manufacturing.domain.ProductionOutput;
import com.inventoryerp.quality.application.QualityCommands.OpenLot;
import com.inventoryerp.quality.application.QualityLotService;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crushing runs by shift (ADR-S016). The plant operator records a <b>draft</b>: feed tonnes out of a ROM stockpile
 * (belt scale / weighbridge / loads / survey), the actual output per size fraction of the plant's grading profile
 * (tonnes, or % of feed for a survey split), recirculated oversize, operating / downtime / crew hours, meter readings
 * per unit (engine hours, kWh, diesel litres) and manual cost lines (wear parts, maintenance).
 *
 * <p><b>Posting</b> runs the multi-stage engine (ADR-S011) in one transaction: a production order of the profile's
 * system-managed BOM (feed location = the feed stockpile, batch = run code) is created and staged; the feed is issued
 * to stage 1 (Dr WIP / Cr stock); diesel × price, kWh × tariff, crew hours × rate and the manual lines are applied as
 * conversion cost to the stage of their unit (Dr WIP / Cr overhead absorbed); each stage then records its output —
 * fractions of earlier stages leave there, the last stage yields the main fraction plus its co-/by-products / waste
 * and the rest is process loss (normal loss = 1 − expected recovery, absorbed; above it abnormal, written off) — and
 * {@link JointCostAllocator} values every stocked fraction into its stockpile. Reversal reverses the order (LIFO).
 */
@Service
public class CrushingRunService {

    static final int SCALE = 6;

    private final CrushingSupport rows;
    private final MfgDb db;
    private final SequenceService seq;
    private final AuditService audit;
    private final EventBus events;
    private final CrushingPlantService plants;
    private final CrushingProfileService profiles;
    private final ProductionService production;
    private final StageExecutionService stages;
    private final QualityLotService qualityLots;

    public CrushingRunService(CrushingSupport rows, SequenceService seq, AuditService audit, EventBus events,
            CrushingPlantService plants, CrushingProfileService profiles, ProductionService production,
            StageExecutionService stages, QualityLotService qualityLots) {
        this.rows = rows;
        this.db = rows.db();
        this.seq = seq;
        this.audit = audit;
        this.events = events;
        this.plants = plants;
        this.profiles = profiles;
        this.production = production;
        this.stages = stages;
        this.qualityLots = qualityLots;
    }

    private static final String SELECT = """
            SELECT r.*, p.code AS "plantCode", p.name AS "plantName", g.code AS "profileCode", g.name AS "profileName",
                   g.version AS "profileVersion", k.code AS "feedStockpileCode", k.name AS "feedStockpileName",
                   f.code AS "feedProductCode", f.name AS "feedProductName", o."orderCode" AS "orderCode"
            FROM "CrushingRun" r
            JOIN "CrushingPlant" p ON p.id = r."plantId"
            JOIN "CrushingGradingProfile" g ON g.id = r."profileId"
            JOIN "QuarryStockpile" k ON k.id = r."feedStockpileId"
            JOIN "Product" f ON f.id = r."feedProductId"
            LEFT JOIN "ProductionOrder" o ON o.id = r."productionOrderId"
            WHERE r."organizationId" = ? AND r."deletedAt" IS NULL""";

    // ================================================================== read

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String plantId, String status, String shift, String from, String to,
            String search, Integer page, Integer pageSize) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        StringBuilder w = new StringBuilder();
        if (CrushingSupport.text(plantId) != null) {
            w.append(" AND r.\"plantId\" = ?");
            params.add(plantId);
        }
        if (CrushingSupport.text(status) != null) {
            w.append(" AND r.status = ?");
            params.add(status);
        }
        if (CrushingSupport.text(shift) != null) {
            w.append(" AND r.shift = ?");
            params.add(shift);
        }
        if (CrushingSupport.date(from) != null) {
            w.append(" AND r.\"runDate\" >= ?");
            params.add(java.sql.Date.valueOf(CrushingSupport.date(from)));
        }
        if (CrushingSupport.date(to) != null) {
            w.append(" AND r.\"runDate\" <= ?");
            params.add(java.sql.Date.valueOf(CrushingSupport.date(to)));
        }
        if (CrushingSupport.text(search) != null) {
            w.append(" AND (r.code ILIKE ? OR r.\"operatorName\" ILIKE ?)");
            params.add("%" + search.trim() + "%");
            params.add("%" + search.trim() + "%");
        }
        int p = CrushingSupport.page(page);
        int size = CrushingSupport.size(pageSize);
        long total = db.jdbc().sql("SELECT count(*) FROM (" + SELECT + w + ") x").params(params).query(Long.class)
                .single();
        List<Object> paged = new ArrayList<>(params);
        paged.add(size);
        paged.add((p - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + w + " ORDER BY r.\"runDate\" DESC, r.code DESC"
                + " LIMIT ? OFFSET ?").params(paged)), p, size, total);
    }

    /** The run with outputs, meters, costs, its preview (balance, yield, cost) and, once posted, the GL entries. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql(SELECT + " AND r.id = ?").params(MfgDb.org(), id));
        if (r == null) {
            throw ApiException.notFound("Crushing run not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("outputs", outputs(id));
        out.put("meters", SqlRows.list(db.jdbc().sql("SELECT m.*, u.code AS \"unitCode\", u.name AS \"unitName\","
                + " u.kind AS \"unitKind\" FROM \"CrushingRunMeter\" m JOIN \"CrushingPlantUnit\" u ON u.id = m.\"unitId\""
                + " WHERE m.\"organizationId\" = ? AND m.\"runId\" = ? ORDER BY u.\"sortOrder\", u.code")
                .params(MfgDb.org(), id)));
        out.put("costs", costs(id));
        out.put("preview", preview(r));
        List<String> postingIds = r.get("productionOrderId") == null ? List.of() : db.jdbc().sql("SELECT id FROM"
                + " \"ProductionStagePosting\" WHERE \"organizationId\" = ? AND \"orderId\" = ?")
                .params(MfgDb.org(), r.get("productionOrderId")).query(String.class).list();
        out.put("journals", rows.journals(StageExecutionService.SOURCE, postingIds));
        return out;
    }

    /** The cost / balance preview of a run (draft: estimated; posted: as booked). */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String id) {
        return preview(rows.require(CrushingRun.class, id, false, "Crushing run not found"));
    }

    private List<Map<String, Object>> outputs(String runId) {
        return SqlRows.list(db.jdbc().sql("""
                SELECT o.*, p.code AS "productCode", p.name AS "productName", l."sizeLabel", k.code AS "stockpileCode",
                       k.name AS "stockpileName", l.stocked
                FROM "CrushingRunOutput" o JOIN "Product" p ON p.id = o."productId"
                JOIN "CrushingGradingLine" l ON l.id = o."gradingLineId"
                LEFT JOIN "QuarryStockpile" k ON k.id = o."stockpileId"
                WHERE o."organizationId" = ? AND o."runId" = ? ORDER BY o."sortOrder\"""").params(MfgDb.org(), runId));
    }

    private List<Map<String, Object>> costs(String runId) {
        return SqlRows.list(db.jdbc().sql("SELECT c.*, u.code AS \"unitCode\" FROM \"CrushingRunCost\" c LEFT JOIN"
                + " \"CrushingPlantUnit\" u ON u.id = c.\"unitId\" WHERE c.\"organizationId\" = ? AND c.\"runId\" = ?"
                + " ORDER BY c.auto DESC, c.kind, c.\"createdAt\"").params(MfgDb.org(), runId));
    }

    private Map<String, Object> preview(Map<String, Object> run) {
        String id = str(run, "id");
        BigDecimal feed = dec(run, "feedTonnes");
        List<Map<String, Object>> outs = outputs(id);
        Map<String, Object> profile = db.byId(CrushingGradingProfile.class, str(run, "profileId"));
        int stageCount = plants.stages(str(run, "plantId")).size();
        List<Fraction> fractions = new ArrayList<>();
        List<BigDecimal> tonnes = new ArrayList<>();
        List<Map<String, Object>> fractionRows = new ArrayList<>();
        for (Map<String, Object> o : outs) {
            BigDecimal actual = dec(o, "actualTonnes");
            tonnes.add(actual);
            Integer st = (Integer) o.get("stageSequence");
            fractions.add(new Fraction(str(o, "id"), st == null ? stageCount : st, "main".equals(o.get("role")), actual));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("outputId", o.get("id"));
            f.put("productName", o.get("productName"));
            f.put("sizeLabel", o.get("sizeLabel"));
            f.put("role", o.get("role"));
            f.put("expectedPct", o.get("expectedPct"));
            f.put("expectedTonnes", o.get("expectedTonnes"));
            f.put("actualTonnes", actual);
            f.put("actualPct", CrushingCalculator.pctOf(actual, feed));
            f.put("varianceTonnes", actual.subtract(dec(o, "expectedTonnes")));
            fractionRows.add(f);
        }
        CrushingCalculator.Yield y = CrushingCalculator.yield(feed, tonnes, dec(profile, "expectedRecoveryPct"));
        String balanceError = null;
        try {
            CrushingCalculator.stagePlan(feed, stageCount, fractions);
        } catch (IllegalArgumentException e) {
            balanceError = e.getMessage();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fractions", fractionRows);
        out.put("outputTonnes", y.outputTonnes());
        out.put("lossTonnes", y.lossTonnes());
        out.put("actualYieldPct", y.actualYieldPct());
        out.put("expectedYieldPct", y.expectedYieldPct());
        out.put("yieldVariancePct", y.yieldVariancePct());
        out.put("recirculatedTonnes", run.get("recirculatedTonnes"));
        out.put("circulatingLoadPct", CrushingCalculator.pctOf(dec(run, "recirculatedTonnes"), feed));
        out.put("balanceError", balanceError);
        Map<String, Object> plant = db.byId(CrushingPlant.class, str(run, "plantId"));
        CrushingCalculator.Performance perf = CrushingCalculator.performance(feed, decOr(run, "operatingHours"),
                dec(run, "downtimeHours"), decOr(plant, "designCapacityTph"));
        out.put("throughputTph", perf.throughputTph());
        out.put("availabilityPct", perf.availabilityPct());
        out.put("utilisationPct", perf.utilisationPct());
        if ("draft".equals(run.get("status"))) {
            Map<String, Object> pile = rows.stockpile(str(run, "feedStockpileId"));
            String loc = str(pile, "locationId");
            BigDecimal avg = rows.averageCost(str(run, "feedProductId"), loc);
            BigDecimal feedCost = feed.multiply(avg).setScale(2, RoundingMode.HALF_UP);
            List<Map<String, Object>> lines = derivedCosts(run, plant);
            BigDecimal conversion = lines.stream().map(l -> dec(l, "amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
            for (Map<String, Object> c : costs(id)) {
                conversion = conversion.add(dec(c, "amount"));
            }
            BigDecimal total = feedCost.add(conversion);
            out.put("feedOnHand", rows.onHand(str(run, "feedProductId"), loc));
            out.put("feedUnitCost", avg);
            out.put("feedCost", feedCost);
            out.put("derivedCosts", lines);
            out.put("conversionCost", conversion);
            out.put("totalCost", total);
            out.put("costPerTonne", y.outputTonnes().signum() > 0 ? total.divide(y.outputTonnes(), 4,
                    RoundingMode.HALF_UP) : null);
            out.put("estimated", true);
        } else {
            out.put("feedCost", run.get("feedCost"));
            out.put("conversionCost", run.get("conversionCost"));
            out.put("abnormalLossValue", run.get("abnormalLossValue"));
            out.put("totalCost", run.get("totalCost"));
            out.put("costPerTonne", run.get("costPerTonne"));
            out.put("estimated", false);
        }
        return out;
    }

    /** Diesel × price, kWh × tariff (per unit) and crew hours × rate — computed from the readings. */
    private List<Map<String, Object>> derivedCosts(Map<String, Object> run, Map<String, Object> plant) {
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal diesel = dec(plant, "dieselCostPerLitre");
        BigDecimal power = dec(plant, "electricityCostPerKwh");
        for (Map<String, Object> m : SqlRows.list(db.jdbc().sql("SELECT m.*, u.code AS \"unitCode\", s.sequence AS"
                + " \"stageSequence\" FROM \"CrushingRunMeter\" m JOIN \"CrushingPlantUnit\" u ON u.id = m.\"unitId\""
                + " LEFT JOIN \"CrushingPlantStage\" s ON s.id = u.\"stageId\" WHERE m.\"organizationId\" = ?"
                + " AND m.\"runId\" = ? ORDER BY u.\"sortOrder\"").params(MfgDb.org(), run.get("id")))) {
            BigDecimal litres = dec(m, "dieselLitres");
            BigDecimal kwh = dec(m, "kwh");
            if (litres.signum() > 0) {
                out.add(cost("diesel", m, litres, diesel, "Diesel · " + m.get("unitCode")));
            }
            if (kwh.signum() > 0) {
                out.add(cost("electricity", m, kwh, power, "Electricity · " + m.get("unitCode")));
            }
        }
        BigDecimal crew = dec(run, "labourHours");
        if (crew.signum() > 0) {
            out.add(cost("labour", null, crew, dec(plant, "labourRatePerHour"), "Crew labour"));
        }
        return out;
    }

    private static Map<String, Object> cost(String kind, Map<String, Object> meter, BigDecimal qty, BigDecimal rate,
            String description) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", kind);
        c.put("unitId", meter == null ? null : meter.get("unitId"));
        c.put("stageSequence", meter == null ? null : meter.get("stageSequence"));
        c.put("quantity", qty);
        c.put("unitRate", rate);
        c.put("amount", CrushingCalculator.cost(qty, rate));
        c.put("description", description);
        return c;
    }

    // ================================================================== draft CRUD

    @Transactional
    public Map<String, Object> create(RunInput in) {
        Map<String, Object> d = header(in);
        d.put("code", seq.next("crushing_run", "CR-", 5));
        d.put("status", "draft");
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> run = db.insert(CrushingRun.class, d);
        writeChildren(run, in);
        audit.recordInTx(AuditInput.of("CrushingRun", str(run, "id"), "create", null, Obj.of("code", run.get("code"),
                "feedTonnes", JsNumbers.dec(in.feedTonnes()))));
        return get(str(run, "id"));
    }

    @Transactional
    public Map<String, Object> update(String id, RunInput in) {
        Map<String, Object> run = rows.require(CrushingRun.class, id, true, "Crushing run not found");
        draftOnly(run);
        Map<String, Object> d = header(in);
        run = db.update(CrushingRun.class, id, d);
        for (String t : List.of("CrushingRunOutput", "CrushingRunMeter", "CrushingRunCost")) {
            db.jdbc().sql("DELETE FROM \"" + t + "\" WHERE \"organizationId\" = ? AND \"runId\" = ?")
                    .params(MfgDb.org(), id).update();
        }
        writeChildren(run, in);
        audit.recordInTx(AuditInput.of("CrushingRun", id, "update", null, Obj.of("feedTonnes",
                JsNumbers.dec(in.feedTonnes()))));
        return get(id);
    }

    @Transactional
    public void remove(String id) {
        Map<String, Object> run = rows.require(CrushingRun.class, id, true, "Crushing run not found");
        draftOnly(run);
        db.update(CrushingRun.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingRun", id, "delete", Obj.of("code", run.get("code")), null));
    }

    private static void draftOnly(Map<String, Object> run) {
        if (!"draft".equals(run.get("status"))) {
            throw ApiException.badRequest("Crushing run " + run.get("code") + " is " + run.get("status")
                    + "; only drafts can be changed");
        }
    }

    private Map<String, Object> header(RunInput in) {
        Map<String, Object> plant = rows.require(CrushingPlant.class, in.plantId(), false, "Crushing plant not found");
        if (!Boolean.TRUE.equals(plant.get("isActive"))) {
            throw ApiException.badRequest("Plant " + plant.get("code") + " is inactive");
        }
        Map<String, Object> profile;
        if (CrushingSupport.text(in.profileId()) != null) {
            profile = rows.require(CrushingGradingProfile.class, in.profileId(), false, "Grading profile not found");
            if (!in.plantId().equals(profile.get("plantId"))) {
                throw ApiException.badRequest("Grading profile " + profile.get("code") + " belongs to another plant");
            }
        } else {
            profile = profiles.soleActive(in.plantId());
            if (profile == null) {
                throw ApiException.badRequest("Choose the grading profile of this run");
            }
        }
        if (!"active".equals(profile.get("status"))) {
            throw ApiException.badRequest("Grading profile " + profile.get("code") + " v" + profile.get("version")
                    + " is " + profile.get("status") + "; use the active version");
        }
        String feedPile = CrushingSupport.text(in.feedStockpileId()) != null ? in.feedStockpileId()
                : str(plant, "feedStockpileId");
        if (feedPile == null) {
            throw ApiException.badRequest("Choose the feed stockpile (the plant has no default)");
        }
        rows.stockpile(feedPile);
        LocalDate date = CrushingSupport.date(in.runDate());
        if (date.isAfter(LocalDate.now(BusinessZone.get()))) {
            throw ApiException.badRequest("The run date cannot be in the future");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("plantId", in.plantId());
        d.put("profileId", profile.get("id"));
        d.put("runDate", date);
        d.put("shift", in.shift() == null ? "day" : in.shift());
        d.put("operatorName", CrushingSupport.text(in.operatorName()));
        d.put("feedStockpileId", feedPile);
        d.put("feedProductId", profile.get("feedProductId"));
        d.put("feedTonnes", in.feedTonnes().setScale(3, RoundingMode.HALF_UP));
        d.put("feedMethod", in.feedMethod() == null ? "belt_scale" : in.feedMethod());
        d.put("outputMethod", in.outputMethod() == null ? "belt_scale" : in.outputMethod());
        d.put("recirculatedTonnes", CrushingSupport.nz(in.recirculatedTonnes()));
        d.put("operatingHours", in.operatingHours());
        d.put("downtimeHours", CrushingSupport.nz(in.downtimeHours()));
        d.put("downtimeReason", CrushingSupport.text(in.downtimeReason()));
        d.put("labourHours", CrushingSupport.nz(in.labourHours()));
        d.put("notes", CrushingSupport.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    private void writeChildren(Map<String, Object> run, RunInput in) {
        String runId = str(run, "id");
        BigDecimal feed = dec(run, "feedTonnes");
        boolean byPct = "survey_pct".equals(run.get("outputMethod"));
        Map<String, RunOutputInput> given = new HashMap<>();
        List<Map<String, Object>> lines = profiles.lines(str(run, "profileId"));
        for (RunOutputInput o : in.outputs() == null ? List.<RunOutputInput>of() : in.outputs()) {
            if (lines.stream().noneMatch(l -> o.gradingLineId().equals(l.get("id")))) {
                throw ApiException.badRequest("Output line " + o.gradingLineId() + " is not part of the grading profile");
            }
            given.put(o.gradingLineId(), o);
        }
        int i = 0;
        for (Map<String, Object> l : lines) {
            RunOutputInput o = given.get(str(l, "id"));
            BigDecimal pct = o == null ? null : o.actualPct();
            BigDecimal actual = byPct ? CrushingCalculator.tonnesOf(feed, pct)
                    : o == null || o.actualTonnes() == null ? BigDecimal.ZERO : o.actualTonnes();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("runId", runId);
            d.put("gradingLineId", l.get("id"));
            d.put("productId", l.get("productId"));
            d.put("role", l.get("role"));
            d.put("stageSequence", l.get("stageSequence"));
            d.put("stockpileId", l.get("stockpileId"));
            d.put("expectedPct", l.get("expectedPct"));
            d.put("expectedTonnes", CrushingCalculator.tonnesOf(feed, dec(l, "expectedPct")));
            d.put("actualPct", byPct ? pct : CrushingCalculator.pctOf(actual, feed));
            d.put("actualTonnes", actual.setScale(3, RoundingMode.HALF_UP));
            d.put("sortOrder", i++);
            db.insert(CrushingRunOutput.class, d);
        }
        java.util.Set<String> units = new java.util.HashSet<>();
        for (RunMeterInput m : in.meters() == null ? List.<RunMeterInput>of() : in.meters()) {
            Map<String, Object> unit = db.jdbc().sql("SELECT id FROM \"CrushingPlantUnit\" WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"plantId\" = ?").params(m.unitId(), MfgDb.org(), run.get("plantId"))
                    .query().listOfRows().stream().findFirst().orElse(null);
            if (unit == null) {
                throw ApiException.badRequest("Unit " + m.unitId() + " is not part of plant's circuit");
            }
            if (!units.add(m.unitId())) {
                throw ApiException.badRequest("Each unit can have one meter line per run");
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("runId", runId);
            d.put("unitId", m.unitId());
            d.put("hoursStart", m.hoursStart());
            d.put("hoursEnd", m.hoursEnd());
            d.put("kwhStart", m.kwhStart());
            d.put("kwhEnd", m.kwhEnd());
            try {
                d.put("runHours", CrushingCalculator.delta(m.hoursStart(), m.hoursEnd(), m.runHours(), "engine-hours"));
                d.put("kwh", CrushingCalculator.delta(m.kwhStart(), m.kwhEnd(), m.kwh(), "kWh"));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }
            d.put("dieselLitres", CrushingSupport.nz(m.dieselLitres()));
            d.put("notes", CrushingSupport.text(m.notes()));
            db.insert(CrushingRunMeter.class, d);
        }
        for (RunCostInput c : in.costs() == null ? List.<RunCostInput>of() : in.costs()) {
            BigDecimal amount = c.amount() != null ? c.amount().setScale(2, RoundingMode.HALF_UP)
                    : CrushingCalculator.cost(c.quantity(), c.unitRate());
            if (c.unitId() != null && db.jdbc().sql("SELECT count(*) FROM \"CrushingPlantUnit\" WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"plantId\" = ?").params(c.unitId(), MfgDb.org(), run.get("plantId"))
                    .query(Long.class).single() == 0) {
                throw ApiException.badRequest("Unit " + c.unitId() + " is not part of plant's circuit");
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("runId", runId);
            d.put("kind", c.kind());
            d.put("unitId", c.unitId());
            d.put("quantity", c.quantity());
            d.put("unitRate", c.unitRate());
            d.put("amount", amount);
            d.put("description", CrushingSupport.text(c.description()));
            d.put("auto", false);
            db.insert(CrushingRunCost.class, d);
        }
    }

    // ================================================================== post

    /** Post a draft run through the multi-stage engine (see the class comment); one transaction, all or nothing. */
    @Transactional
    public Map<String, Object> post(String id) {
        Map<String, Object> run = rows.require(CrushingRun.class, id, true, "Crushing run not found");
        draftOnly(run);
        String code = str(run, "code");
        Map<String, Object> plant = rows.require(CrushingPlant.class, str(run, "plantId"), false,
                "Crushing plant not found");
        if (!Boolean.TRUE.equals(plant.get("isActive"))) {
            throw ApiException.badRequest("Plant " + plant.get("code") + " is inactive");
        }
        Map<String, Object> profile = rows.require(CrushingGradingProfile.class, str(run, "profileId"), false,
                "Grading profile not found");
        if (!"active".equals(profile.get("status")) || profile.get("bomId") == null) {
            throw ApiException.badRequest("Grading profile " + profile.get("code") + " v" + profile.get("version")
                    + " is " + profile.get("status") + "; edit the run to use the active version");
        }
        List<Map<String, Object>> outs = outputs(id);
        BigDecimal feed = dec(run, "feedTonnes");
        List<Fraction> fractions = new ArrayList<>();
        for (Map<String, Object> o : outs) {
            Integer st = (Integer) o.get("stageSequence");
            fractions.add(new Fraction(str(o, "id"), st == null ? Integer.MAX_VALUE : st, "main".equals(o.get("role")),
                    dec(o, "actualTonnes")));
        }
        // The stage count of the order = operations of the profile's routing (fixed when it was activated).
        String dateIso = String.valueOf(run.get("runDate"));
        Map<String, Object> pile = rows.stockpile(str(run, "feedStockpileId"));
        if (!Boolean.TRUE.equals(pile.get("isActive"))) {
            throw ApiException.badRequest("Stockpile " + pile.get("code") + " is inactive");
        }
        Map<String, Object> mainLine = outs.stream().filter(o -> "main".equals(o.get("role"))).findFirst()
                .orElseThrow(() -> ApiException.badRequest("The grading profile has no main fraction"));
        BigDecimal mainPct = dec(mainLine, "expectedPct");
        BigDecimal plannedQty = feed.multiply(mainPct).divide(CrushingProfileService.HUNDRED, SCALE,
                RoundingMode.HALF_UP);
        Map<String, Object> mainPile = rows.stockpile(str(mainLine, "stockpileId"));

        // 1) The production order of the profile's BOM, staged from its routing.
        Map<String, Object> order = production.createOrder(new CreateOrder(str(profile, "bomId"), null, null,
                str(pile, "locationId"), str(mainPile, "locationId"), null, plannedQty, dateIso, code, null, dateIso,
                "Crushing run " + code + " · " + plant.get("code"), null, null));
        String orderId = str(order, "id");
        db.update(ProductionOrder.class, orderId, Obj.of("status", "confirmed", "updatedBy", TenantContext.userId()));
        stages.initialize(orderId, new InitializeStages("Crushing run " + code));
        List<Map<String, Object>> orderStages = SqlRows.list(ProductionOrderStage.class, db.jdbc().sql("SELECT * FROM"
                + " \"ProductionOrderStage\" WHERE \"organizationId\" = ? AND \"orderId\" = ? ORDER BY sequence")
                .params(MfgDb.org(), orderId));
        int n = orderStages.size();
        List<StagePlan> plan;
        try {
            plan = CrushingCalculator.stagePlan(feed, n, fractions);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        // Normal process loss = 1 − expected recovery of the profile, absorbed at the last stage.
        BigDecimal recovery = dec(profile, "expectedRecoveryPct");
        db.update(ProductionOrderStage.class, str(orderStages.getLast(), "id"), Obj.of("expectedYieldPct",
                recovery.signum() > 0 ? recovery.min(CrushingProfileService.HUNDRED) : CrushingProfileService.HUNDRED));

        // 2) Feed out of the stockpile into stage 1.
        Map<String, Object> material = db.children(ProductionMaterial.class, "orderId", orderId, "sequence ASC")
                .getFirst();
        stages.issue(str(orderStages.getFirst(), "id"), new StageIssue(List.of(new IssueLine(str(material, "id"), null,
                feed, null)), dateIso, "Feed · " + code + " · " + pile.get("code")));

        // 3) Conversion cost per stage (derived from meters + crew hours, plus the manual lines).
        List<Map<String, Object>> derived = derivedCosts(run, plant);
        for (Map<String, Object> c : derived) {
            Map<String, Object> d = new LinkedHashMap<>(c);
            d.put("runId", id);
            d.put("auto", true);
            db.insert(CrushingRunCost.class, d);
        }
        Map<String, Integer> unitStage = new HashMap<>();
        for (Map<String, Object> u : SqlRows.list(db.jdbc().sql("SELECT u.id, s.sequence FROM \"CrushingPlantUnit\" u"
                + " LEFT JOIN \"CrushingPlantStage\" s ON s.id = u.\"stageId\" WHERE u.\"organizationId\" = ? AND"
                + " u.\"plantId\" = ?").params(MfgDb.org(), plant.get("id")))) {
            unitStage.put(str(u, "id"), (Integer) u.get("sequence"));
        }
        BigDecimal conversion = BigDecimal.ZERO;
        for (Map<String, Object> c : costs(id)) {
            BigDecimal amount = dec(c, "amount");
            if (amount.signum() <= 0) {
                continue;
            }
            Integer st = c.get("unitId") == null ? null : unitStage.get(str(c, "unitId"));
            int stageIdx = st == null ? n - 1 : Math.min(Math.max(st, 1), n) - 1;
            stages.conversion(str(orderStages.get(stageIdx), "id"), new StageConversion(
                    amount, conversionKind(str(c, "kind")), null, dateIso, label(c) + " · " + code));
            db.update(CrushingRunCost.class, str(c, "id"), Obj.of("stageSequence", stageIdx + 1, "stagePostingId",
                    latestPosting(orderId)));
            conversion = conversion.add(amount);
        }

        // 4) Output stage by stage: fractions of the stage, pass-through / main good, loss at the last stage.
        Map<String, String> outputIdByProduct = new HashMap<>();
        for (Map<String, Object> o : db.children(ProductionOutput.class, "orderId", orderId, null)) {
            outputIdByProduct.put(str(o, "productId"), str(o, "id"));
        }
        Map<String, Map<String, Object>> outById = new HashMap<>();
        outs.forEach(o -> outById.put(str(o, "id"), o));
        for (int i = 0; i < n; i++) {
            StagePlan sp = plan.get(i);
            List<OutputQty> qty = new ArrayList<>();
            for (Fraction f : sp.fractions()) {
                String pid = str(outById.get(f.key()), "productId");
                qty.add(new OutputQty(outputIdByProduct.get(pid), f.tonnes(), code));
            }
            boolean last = i == n - 1;
            stages.output(str(orderStages.get(i), "id"), new StageOutput(sp.good(), last ? sp.loss() : BigDecimal.ZERO,
                    null, true, qty, code, null, dateIso, (last ? "Screened products · " : "Stage output · ") + code));
        }

        // 5) Snapshot values per fraction, totals, quality final lots, event.
        Map<String, BigDecimal[]> byOutput = new HashMap<>();
        BigDecimal feedCost = BigDecimal.ZERO;
        BigDecimal abnormal = BigDecimal.ZERO;
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT l.* FROM \"ProductionStagePostingLine\" l JOIN"
                + " \"ProductionStagePosting\" p ON p.id = l.\"postingId\" WHERE l.\"organizationId\" = ? AND"
                + " p.\"orderId\" = ? AND p.\"reversedAt\" IS NULL").params(MfgDb.org(), orderId))) {
            String role = str(l, "role");
            if ("material".equals(role)) {
                feedCost = feedCost.add(dec(l, "value"));
            } else if ("abnormal_scrap".equals(role)) {
                abnormal = abnormal.add(dec(l, "value"));
            } else if (List.of("main_output", "co_product", "by_product", "waste").contains(role)) {
                String key = "main_output".equals(role) ? "main" : str(l, "productId");
                byOutput.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                byOutput.get(key)[0] = byOutput.get(key)[0].add(dec(l, "quantity"));
                byOutput.get(key)[1] = byOutput.get(key)[1].add(dec(l, "value"));
            }
        }
        BigDecimal stockedTonnes = BigDecimal.ZERO;
        BigDecimal stockedValue = BigDecimal.ZERO;
        for (Map<String, Object> o : outs) {
            BigDecimal[] qv = byOutput.get("main".equals(o.get("role")) ? "main" : str(o, "productId"));
            BigDecimal value = qv == null ? BigDecimal.ZERO : qv[1];
            BigDecimal t = dec(o, "actualTonnes");
            db.update(CrushingRunOutput.class, str(o, "id"), Obj.of("value", value, "unitCost", t.signum() > 0
                    ? value.divide(t, SCALE, RoundingMode.HALF_UP) : null));
            if (Boolean.TRUE.equals(o.get("stocked")) && t.signum() > 0) {
                stockedTonnes = stockedTonnes.add(t);
                stockedValue = stockedValue.add(value);
                Map<String, Object> k = rows.stockpile(str(o, "stockpileId"));
                qualityLots.openLotIfPlanned(new OpenLot("final", str(o, "productId"), null, code, t, null,
                        str(k, "locationId"), "crushing_run", id, str(o, "id"), code, null, null, null, null, "source",
                        false, "Crushing run " + code + " · " + o.get("productName")));
            }
        }
        CrushingCalculator.Yield y = CrushingCalculator.yield(feed, outs.stream().map(o -> dec(o, "actualTonnes"))
                .toList(), recovery);
        BigDecimal total = feedCost.add(conversion);
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("status", "posted");
        upd.put("productionOrderId", orderId);
        upd.put("outputTonnes", y.outputTonnes());
        upd.put("lossTonnes", y.lossTonnes());
        upd.put("actualYieldPct", y.actualYieldPct());
        upd.put("expectedYieldPct", y.expectedYieldPct());
        upd.put("feedCost", feedCost.setScale(2, RoundingMode.HALF_UP));
        upd.put("conversionCost", conversion.setScale(2, RoundingMode.HALF_UP));
        upd.put("abnormalLossValue", abnormal.setScale(2, RoundingMode.HALF_UP));
        upd.put("totalCost", total.setScale(2, RoundingMode.HALF_UP));
        upd.put("costPerTonne", stockedTonnes.signum() > 0 ? stockedValue.divide(stockedTonnes, SCALE,
                RoundingMode.HALF_UP) : null);
        upd.put("postedAt", Instant.now());
        upd.put("postedBy", TenantContext.userId());
        upd.put("updatedBy", TenantContext.userId());
        db.update(CrushingRun.class, id, upd);
        audit.recordInTx(AuditInput.of("CrushingRun", id, "post", null, Obj.of("code", code, "orderId", orderId,
                "feedTonnes", JsNumbers.dec(feed), "outputTonnes", JsNumbers.dec(y.outputTonnes()))));
        events.publish(DomainEvents.CRUSHING_RUN_POSTED, Obj.of("organizationId", MfgDb.org(), "runId", id, "code",
                code, "plantId", plant.get("id"), "productionOrderId", orderId, "feedTonnes", JsNumbers.dec(feed),
                "outputTonnes", JsNumbers.dec(y.outputTonnes()), "totalCost", JsNumbers.dec(total)));
        return get(id);
    }

    /** Reverse a posted run: the engine reverses every stage posting (LIFO) and cancels the order. */
    @Transactional
    public Map<String, Object> reverse(String id, String reason) {
        Map<String, Object> run = rows.require(CrushingRun.class, id, true, "Crushing run not found");
        if (!"posted".equals(run.get("status"))) {
            throw ApiException.badRequest("Only a posted crushing run can be reversed (run " + run.get("code") + " is "
                    + run.get("status") + ")");
        }
        stages.reverseOrder(str(run, "productionOrderId"), "Crushing run " + run.get("code") + ": " + reason);
        db.update(CrushingRun.class, id, Obj.of("status", "reversed", "reversedAt", Instant.now(), "reversedBy",
                TenantContext.userId(), "reversalReason", reason, "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingRun", id, "cancel", null, Obj.of("code", run.get("code"), "reason",
                reason)));
        return get(id);
    }

    private String latestPosting(String orderId) {
        return db.jdbc().sql("SELECT id FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ? AND \"orderId\" = ?"
                + " ORDER BY \"createdAt\" DESC, code DESC LIMIT 1").params(MfgDb.org(), orderId).query(String.class)
                .optional().orElse(null);
    }

    /** Run cost kind → stage conversion kind. */
    static String conversionKind(String kind) {
        return switch (kind) {
            case "diesel", "wear_parts" -> "machine";
            case "electricity" -> "utility";
            case "labour" -> "labour";
            default -> "overhead";
        };
    }

    private static String label(Map<String, Object> c) {
        return c.get("description") != null ? str(c, "description") : String.valueOf(c.get("kind")).replace('_', ' ');
    }

    private static BigDecimal decOr(Map<String, Object> row, String key) {
        return row == null || row.get(key) == null ? null : dec(row, key);
    }
}
