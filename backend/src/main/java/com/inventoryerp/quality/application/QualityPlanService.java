package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quality.application.QualityCommands.PlanInput;
import com.inventoryerp.quality.application.QualityCommands.PlanPatch;
import com.inventoryerp.quality.application.QualityCommands.PlanQuery;
import com.inventoryerp.quality.domain.QualityInspectionPlan;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inspection plans (ADR-S013 §3): which product is inspected at which stage (incoming / in-process / final), against
 * which specification (the active version of its code when a lot opens), with which sampling and hold policy.
 * {@link #findPlan} is the matching rule hooks use.
 */
@Service
public class QualityPlanService {

    static final String AUDIT_ENTITY = "QualityInspectionPlan";

    private final QualityDb db;
    private final QualitySpecService specs;
    private final AuditService audit;

    public QualityPlanService(QualityDb db, QualitySpecService specs, AuditService audit) {
        this.db = db;
        this.specs = specs;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT pl.*, p.name AS "productName", p.sku AS "productSku", s.code AS "specificationCode",
                   s.name AS "specificationName", s.version AS "specificationVersion", s.status AS "specificationStatus",
                   pa.name AS "supplierName"
            FROM "QualityInspectionPlan" pl
            LEFT JOIN "Product" p ON p.id = pl."productId" AND p."organizationId" = pl."organizationId"
            LEFT JOIN "QualitySpecification" s ON s.id = pl."specificationId"
            LEFT JOIN "Partner" pa ON pa.id = pl."supplierPartnerId" AND pa."organizationId" = pl."organizationId"
            """;

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PlanQuery q) {
        int page = Q.page(q.page());
        int size = Q.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE pl.\"organizationId\" = ? AND pl.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(QualityDb.org()));
        if (Q.has(q.search())) {
            where.append(" AND (pl.code ILIKE ? OR pl.name ILIKE ? OR p.name ILIKE ? OR s.code ILIKE ?)");
            String like = Q.like(q.search());
            for (int i = 0; i < 4; i++) {
                params.add(like);
            }
        }
        Q.eq(where, params, "pl.stage", q.stage());
        Q.eq(where, params, "pl.\"productId\"", q.productId());
        Q.eq(where, params, "pl.\"isActive\"", q.active());
        long total = db.jdbc().sql("SELECT count(*) FROM (" + SELECT + where + ") c").params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY pl.code LIMIT ? OFFSET ?")
                .params(params)), page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql(SELECT + " WHERE pl.id = ? AND pl.\"organizationId\" = ?"
                + " AND pl.\"deletedAt\" IS NULL").params(id, QualityDb.org()));
        if (r == null) {
            throw ApiException.notFound("Inspection plan not found");
        }
        String active = specs.activeVersionOf(Q.str(r, "specificationId"));
        r.put("activeSpecificationId", active);
        return r;
    }

    @Transactional
    public Map<String, Object> create(PlanInput in) {
        String code = in.code().trim().toUpperCase(Locale.ROOT);
        if (!db.list(QualityInspectionPlan.class, " AND code = ?", code).isEmpty()) {
            throw ApiException.conflict("Inspection plan " + code + " already exists");
        }
        specs.requireProduct(in.productId());
        specs.require(in.specificationId());
        requireSupplier(in.supplierPartnerId());
        if (Q.has(in.supplierPartnerId()) && !"incoming".equals(in.stage())) {
            throw ApiException.badRequest("Only an incoming plan can be supplier-specific.");
        }
        if (Q.has(in.stageKey()) && !"in_process".equals(in.stage())) {
            throw ApiException.badRequest("Only an in-process plan has a stage key.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", in.name().trim());
        data.put("stage", in.stage());
        data.put("productId", in.productId());
        data.put("supplierPartnerId", Q.trim(in.supplierPartnerId()));
        data.put("stageKey", Q.trim(in.stageKey()));
        data.put("specificationId", in.specificationId());
        data.put("samplingMethod", Q.or(in.samplingMethod(), "fixed"));
        data.put("sampleSize", in.sampleSize());
        data.put("samplePct", in.samplePct());
        data.put("perQuantity", in.perQuantity());
        data.put("minSamples", Q.or(in.minSamples(), 1));
        data.put("maxSamples", in.maxSamples());
        data.put("holdPolicy", Q.or(in.holdPolicy(), "none"));
        data.put("independentRelease", Q.or(in.independentRelease(), true));
        data.put("autoNcr", Q.or(in.autoNcr(), true));
        data.put("isActive", Q.or(in.isActive(), true));
        data.put("notes", Q.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        checkSampling(data);
        String id = Q.str(db.insert(QualityInspectionPlan.class, data), "id");
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, Obj.of("code", code, "stage", in.stage())));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, PlanPatch in) {
        Map<String, Object> plan = db.require(QualityInspectionPlan.class, id, true, "Inspection plan not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.name() != null) {
            data.put("name", in.name().trim());
        }
        if (in.supplierPartnerId() != null) {
            requireSupplier(in.supplierPartnerId());
            if (Q.has(in.supplierPartnerId()) && !"incoming".equals(plan.get("stage"))) {
                throw ApiException.badRequest("Only an incoming plan can be supplier-specific.");
            }
            data.put("supplierPartnerId", Q.trim(in.supplierPartnerId()));
        }
        if (in.stageKey() != null) {
            if (Q.has(in.stageKey()) && !"in_process".equals(plan.get("stage"))) {
                throw ApiException.badRequest("Only an in-process plan has a stage key.");
            }
            data.put("stageKey", Q.trim(in.stageKey()));
        }
        if (in.specificationId() != null) {
            specs.require(in.specificationId());
            data.put("specificationId", in.specificationId());
        }
        putIf(data, "samplingMethod", in.samplingMethod());
        putIf(data, "sampleSize", in.sampleSize());
        putIf(data, "samplePct", in.samplePct());
        putIf(data, "perQuantity", in.perQuantity());
        putIf(data, "minSamples", in.minSamples());
        putIf(data, "maxSamples", in.maxSamples());
        putIf(data, "holdPolicy", in.holdPolicy());
        putIf(data, "independentRelease", in.independentRelease());
        putIf(data, "autoNcr", in.autoNcr());
        putIf(data, "isActive", in.isActive());
        if (in.notes() != null) {
            data.put("notes", Q.trim(in.notes()));
        }
        Map<String, Object> merged = new LinkedHashMap<>(plan);
        merged.putAll(data);
        checkSampling(merged);
        data.put("updatedBy", TenantContext.userId());
        db.update(QualityInspectionPlan.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", null, Obj.of("code", plan.get("code"))));
        return get(id);
    }

    @Transactional
    public void remove(String id) {
        Map<String, Object> plan = db.require(QualityInspectionPlan.class, id, true, "Inspection plan not found");
        db.update(QualityInspectionPlan.class, id, Obj.of("deletedAt", Instant.now(), "isActive", false,
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "delete", null, Obj.of("code", plan.get("code"))));
    }

    /**
     * The active plan for a stage and product, most specific first: incoming plans of the supplier before generic
     * ones; in-process plans of the stage key before stage-less ones. Null when none applies.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> findPlan(String stage, String productId, String supplierPartnerId, String stageKey) {
        if (productId == null) {
            return null;
        }
        List<Map<String, Object>> rows = db.list(QualityInspectionPlan.class, " AND \"isActive\" = true AND stage = ?"
                + " AND \"productId\" = ? AND (\"supplierPartnerId\" IS NULL OR \"supplierPartnerId\" = ?)"
                + " AND (\"stageKey\" IS NULL OR \"stageKey\" = ?)"
                + " ORDER BY (\"supplierPartnerId\" IS NULL), (\"stageKey\" IS NULL), \"createdAt\" DESC LIMIT 1",
                stage, productId, supplierPartnerId == null ? "" : supplierPartnerId, stageKey == null ? "" : stageKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> requirePlan(String id) {
        return db.require(QualityInspectionPlan.class, id, false, "Inspection plan not found");
    }

    private void requireSupplier(String partnerId) {
        if (Q.has(partnerId) && !db.exists("Partner", partnerId, true)) {
            throw ApiException.notFound("Supplier not found");
        }
    }

    private static void checkSampling(Map<String, Object> p) {
        String method = String.valueOf(p.get("samplingMethod"));
        if ("percent".equals(method) && p.get("samplePct") == null) {
            throw ApiException.badRequest("Sampling by percent needs samplePct.");
        }
        if ("per_quantity".equals(method) && p.get("perQuantity") == null) {
            throw ApiException.badRequest("Sampling per quantity needs perQuantity.");
        }
        Object min = p.get("minSamples");
        Object max = p.get("maxSamples");
        if (min instanceof Number a && max instanceof Number b && a.intValue() > b.intValue()) {
            throw ApiException.badRequest("minSamples cannot exceed maxSamples.");
        }
    }

    private static void putIf(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    static SamplingCalculator.Sampling sampling(Map<String, Object> plan) {
        return new SamplingCalculator.Sampling(Q.str(plan, "samplingMethod"), (Integer) plan.get("sampleSize"),
                (BigDecimal) plan.get("samplePct"), (BigDecimal) plan.get("perQuantity"),
                (Integer) plan.get("minSamples"), (Integer) plan.get("maxSamples"));
    }
}
