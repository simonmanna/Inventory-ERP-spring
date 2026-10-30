package com.inventoryerp.quarry.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quarry.application.QuarryCommands.PlanInput;
import com.inventoryerp.quarry.domain.QuarryExtractionPlan;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Extraction plans and production targets per site / zone (incl. its benches and faces) / material and period, with
 * the actual net tonnes of posted extraction logs and the {@link TargetVariance} against the target.
 */
@Service
public class QuarryPlanService {

    private final QuarryDb db;
    private final QuarrySiteService sites;
    private final SequenceService sequence;
    private final AuditService audit;

    public QuarryPlanService(QuarryDb db, QuarrySiteService sites, SequenceService sequence, AuditService audit) {
        this.db = db;
        this.sites = sites;
        this.sequence = sequence;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT p.*, s.code AS "siteCode", s.name AS "siteName", z.code AS "zoneCode", z.name AS "zoneName",
                   m.code AS "materialCode", m.name AS "materialName"
            FROM "QuarryExtractionPlan" p
            JOIN "QuarrySite" s ON s.id = p."siteId"
            LEFT JOIN "QuarryZone" z ON z.id = p."zoneId"
            LEFT JOIN "QuarryMaterial" m ON m.id = p."materialId"
            """;

    /**
     * Actual net tonnes and cost of posted logs matching a plan: same site, the plan's zone or a bench / face below
     * it, the plan's material, extracted within the plan period.
     */
    static final String ACTUAL = """
            SELECT COALESCE(SUM(l."netTonnes"), 0) AS tonnes, COALESCE(SUM(l."totalCost"), 0) AS cost, count(*) AS logs
            FROM "QuarryExtractionLog" l
            WHERE l."organizationId" = ? AND l.status = 'posted' AND l."deletedAt" IS NULL AND l."siteId" = ?
              AND l."extractedOn" BETWEEN ? AND ?
              AND (?::text IS NULL OR l."materialId" = ?)
              AND (?::text IS NULL OR l."zoneId" IN (
                    SELECT z.id FROM "QuarryZone" z WHERE z."organizationId" = l."organizationId" AND (z.id = ?
                      OR z."parentId" = ? OR z."parentId" IN (SELECT c.id FROM "QuarryZone" c WHERE c."parentId" = ?))))
            """;

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String siteId, String status, LocalDate activeOn, String search,
            Integer page, Integer pageSize) {
        int p = QuarryDb.page(page);
        int size = QuarryDb.size(pageSize);
        StringBuilder where = new StringBuilder(" WHERE p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org()));
        if (!QuarryDb.blank(siteId)) {
            where.append(" AND p.\"siteId\" = ?");
            params.add(siteId);
        }
        if (!QuarryDb.blank(status)) {
            where.append(" AND p.status = ?");
            params.add(status);
        }
        if (activeOn != null) {
            where.append(" AND p.\"periodStart\" <= ? AND p.\"periodEnd\" >= ?");
            params.add(java.sql.Date.valueOf(activeOn));
            params.add(java.sql.Date.valueOf(activeOn));
        }
        if (!QuarryDb.blank(search)) {
            where.append(" AND (p.code ILIKE ? OR p.name ILIKE ?)");
            params.add(QuarryDb.like(search));
            params.add(QuarryDb.like(search));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"QuarryExtractionPlan\" p" + where).params(params)
                .query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY p.\"periodStart\" DESC, p.code LIMIT ? OFFSET ?").params(pp))) {
            rows.add(withProgress(r));
        }
        return PageResult.of(rows, p, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        require(id, false);
        return withProgress(SqlRows.one(db.jdbc().sql(SELECT + " WHERE p.id = ? AND p.\"organizationId\" = ?")
                .params(id, QuarryDb.org())));
    }

    Map<String, Object> require(String id, boolean lock) {
        return db.require(QuarryExtractionPlan.class, id, lock, "Extraction plan not found");
    }

    /** The plan row plus {@code actual} (tonnes, cost, logs, cost per tonne) and {@code variance}. */
    Map<String, Object> withProgress(Map<String, Object> plan) {
        Map<String, Object> out = new LinkedHashMap<>(plan);
        Map<String, Object> a = actual(plan);
        BigDecimal tonnes = SqlRows.dec(a, "tonnes");
        BigDecimal cost = SqlRows.dec(a, "cost");
        out.put("actualTonnes", tonnes);
        out.put("actualCost", cost);
        out.put("logCount", a.get("logs"));
        out.put("actualCostPerTonne", tonnes.signum() > 0 ? cost.divide(tonnes, 4, RoundingMode.HALF_UP) : null);
        out.put("variance", TargetVariance.of(SqlRows.dec(plan, "targetTonnes"), tonnes,
                (LocalDate) plan.get("periodStart"), (LocalDate) plan.get("periodEnd"),
                LocalDate.now(BusinessZone.get())));
        return out;
    }

    Map<String, Object> actual(Map<String, Object> plan) {
        String zone = (String) plan.get("zoneId");
        String material = (String) plan.get("materialId");
        return db.jdbc().sql(ACTUAL).params(QuarryDb.org(), plan.get("siteId"),
                java.sql.Date.valueOf((LocalDate) plan.get("periodStart")),
                java.sql.Date.valueOf((LocalDate) plan.get("periodEnd")), material, material, zone, zone, zone, zone)
                .query().singleRow();
    }

    @Transactional
    public Map<String, Object> create(PlanInput in) {
        Map<String, Object> d = data(in);
        String code = QuarryDb.text(in.code());
        d.put("code", code == null ? sequence.next("quarry_plan", "QP-", 4) : code.toUpperCase());
        assertCodeFree((String) d.get("code"), null);
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarryExtractionPlan.class, d);
        audit.recordInTx(AuditInput.of("QuarryExtractionPlan", (String) row.get("id"), "create", null, row));
        return get((String) row.get("id"));
    }

    @Transactional
    public Map<String, Object> update(String id, PlanInput in) {
        Map<String, Object> before = require(id, true);
        Map<String, Object> d = data(in);
        String code = QuarryDb.text(in.code());
        if (code != null && !code.equalsIgnoreCase((String) before.get("code"))) {
            assertCodeFree(code.toUpperCase(), id);
            d.put("code", code.toUpperCase());
        }
        Map<String, Object> after = db.update(QuarryExtractionPlan.class, id, d);
        audit.recordInTx(AuditInput.of("QuarryExtractionPlan", id, "update", before, after));
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        Map<String, Object> before = require(id, true);
        db.update(QuarryExtractionPlan.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of("QuarryExtractionPlan", id, "delete", before, null));
    }

    private Map<String, Object> data(PlanInput in) {
        if (in.periodEnd().isBefore(in.periodStart())) {
            throw ApiException.badRequest("The period end must be on or after its start");
        }
        Map<String, Object> site = sites.requireSite(in.siteId(), false);
        String zone = QuarryDb.text(in.zoneId());
        if (zone != null) {
            sites.zoneOfSite(zone, (String) site.get("id"));
        }
        String material = QuarryDb.text(in.materialId());
        db.requireRef("QuarryMaterial", material, true, "Material not found");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", in.name().trim());
        d.put("siteId", site.get("id"));
        d.put("zoneId", zone);
        d.put("materialId", material);
        d.put("periodStart", in.periodStart());
        d.put("periodEnd", in.periodEnd());
        d.put("targetTonnes", in.targetTonnes());
        d.put("budgetCostPerTonne", in.budgetCostPerTonne());
        d.put("status", in.status() == null ? "active" : in.status());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    private void assertCodeFree(String code, String exceptId) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"QuarryExtractionPlan\" WHERE \"organizationId\" = ? AND code = ?"
                + " AND \"deletedAt\" IS NULL AND id IS DISTINCT FROM ?").params(QuarryDb.org(), code, exceptId)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("An extraction plan with code " + code + " already exists");
        }
    }
}
