package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quality.application.QualityCommands.SievePointInput;
import com.inventoryerp.quality.application.QualityCommands.SpecInput;
import com.inventoryerp.quality.application.QualityCommands.SpecParameterInput;
import com.inventoryerp.quality.application.QualityCommands.SpecPatch;
import com.inventoryerp.quality.application.QualityCommands.SpecQuery;
import com.inventoryerp.quality.application.SpecEvaluator.Parameter;
import com.inventoryerp.quality.application.SpecEvaluator.SievePoint;
import com.inventoryerp.quality.domain.QualitySpecParameter;
import com.inventoryerp.quality.domain.QualitySpecification;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Versioned specifications (ADR-S013 §3): {@code draft → active → superseded | archived}, one active and at most one
 * draft version per code. Parameters are editable on drafts only; {@code revise} copies a version into a new draft.
 */
@Service
public class QualitySpecService {

    static final String AUDIT_ENTITY = "QualitySpecification";

    private final QualityDb db;
    private final AuditService audit;

    public QualitySpecService(QualityDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(SpecQuery q) {
        int page = Q.page(q.page());
        int size = Q.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ? AND s.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(QualityDb.org()));
        if (Q.has(q.search())) {
            where.append(" AND (s.code ILIKE ? OR s.name ILIKE ? OR s.grade ILIKE ? OR p.name ILIKE ?)");
            String like = Q.like(q.search());
            for (int i = 0; i < 4; i++) {
                params.add(like);
            }
        }
        Q.eq(where, params, "s.status", q.status());
        Q.eq(where, params, "s.\"productId\"", q.productId());
        String from = " FROM \"QualitySpecification\" s LEFT JOIN \"Product\" p ON p.id = s.\"productId\""
                + " AND p.\"organizationId\" = s.\"organizationId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT s.*, p.name AS \"productName\","
                + " (SELECT count(*) FROM \"QualitySpecParameter\" x WHERE x.\"specificationId\" = s.id)"
                + " AS \"parameterCount\"" + from + where + " ORDER BY s.code, s.version DESC LIMIT ? OFFSET ?")
                .params(params));
        return PageResult.of(rows, page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> s = require(id);
        Map<String, Object> out = new LinkedHashMap<>(s);
        out.put("productName", productName(Q.str(s, "productId")));
        out.put("parameters", parameterRows(id));
        out.put("versions", SqlRows.list(db.jdbc().sql("SELECT id, version, status, \"activatedAt\", \"createdAt\""
                + " FROM \"QualitySpecification\" WHERE \"organizationId\" = ? AND code = ? AND \"deletedAt\" IS NULL"
                + " ORDER BY version DESC").params(QualityDb.org(), s.get("code"))));
        return out;
    }

    /** Parameter rows of a specification version, in display order. */
    List<Map<String, Object>> parameterRows(String specificationId) {
        return db.list(QualitySpecParameter.class, " AND \"specificationId\" = ? ORDER BY \"sortOrder\", code",
                specificationId);
    }

    /** Parameters of a version as evaluator input. */
    List<Parameter> parameters(String specificationId) {
        List<Parameter> out = new ArrayList<>();
        for (Map<String, Object> r : parameterRows(specificationId)) {
            out.add(toParameter(r));
        }
        return out;
    }

    static Parameter toParameter(Map<String, Object> r) {
        List<SievePoint> envelope = new ArrayList<>();
        if (r.get("sieveEnvelope") instanceof JsonNode env && env.isArray()) {
            for (JsonNode p : env) {
                envelope.add(new SievePoint(num(p, "sieveMm"), num(p, "minPassingPct"), num(p, "maxPassingPct")));
            }
        }
        return new Parameter(Q.str(r, "id"), Q.str(r, "code"), Q.str(r, "name"), Q.str(r, "kind"),
                Q.str(r, "unit"), (BigDecimal) r.get("minValue"), (BigDecimal) r.get("maxValue"),
                (BigDecimal) r.get("targetValue"), Q.str(r, "expectedText"), envelope,
                !Boolean.FALSE.equals(r.get("isCritical")));
    }

    private static BigDecimal num(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : new BigDecimal(v.asString());
    }

    /** The active version of the specification family of {@code specificationId}, or null. */
    String activeVersionOf(String specificationId) {
        return db.jdbc().sql("SELECT a.id FROM \"QualitySpecification\" s JOIN \"QualitySpecification\" a"
                + " ON a.\"organizationId\" = s.\"organizationId\" AND a.code = s.code AND a.status = 'active'"
                + " AND a.\"deletedAt\" IS NULL WHERE s.id = ? AND s.\"organizationId\" = ? LIMIT 1")
                .params(specificationId, QualityDb.org()).query(String.class).optional().orElse(null);
    }

    Map<String, Object> require(String id) {
        return db.require(QualitySpecification.class, id, false, "Specification not found");
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public Map<String, Object> create(SpecInput in) {
        String code = in.code().trim().toUpperCase(java.util.Locale.ROOT);
        long existing = db.jdbc().sql("SELECT count(*) FROM \"QualitySpecification\" WHERE \"organizationId\" = ?"
                + " AND code = ? AND \"deletedAt\" IS NULL").params(QualityDb.org(), code).query(Long.class).single();
        if (existing > 0) {
            throw ApiException.conflict("Specification " + code + " already exists; revise it to make a new version.");
        }
        requireProduct(in.productId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("version", 1);
        data.put("name", in.name().trim());
        data.put("productId", Q.trim(in.productId()));
        data.put("grade", Q.trim(in.grade()));
        data.put("standard", Q.trim(in.standard()));
        data.put("effectiveFrom", in.effectiveFrom());
        data.put("notes", Q.trim(in.notes()));
        data.put("status", "draft");
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> s = db.insert(QualitySpecification.class, data);
        String id = Q.str(s, "id");
        writeParameters(id, in.parameters() == null ? List.of() : in.parameters());
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, Obj.of("code", code, "version", 1)));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, SpecPatch in) {
        Map<String, Object> s = db.require(QualitySpecification.class, id, true, "Specification not found");
        requireDraft(s);
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.name() != null) {
            data.put("name", in.name().trim());
        }
        if (in.productId() != null) {
            requireProduct(in.productId());
            data.put("productId", Q.trim(in.productId()));
        }
        if (in.grade() != null) {
            data.put("grade", Q.trim(in.grade()));
        }
        if (in.standard() != null) {
            data.put("standard", Q.trim(in.standard()));
        }
        if (in.effectiveFrom() != null) {
            data.put("effectiveFrom", in.effectiveFrom());
        }
        if (in.notes() != null) {
            data.put("notes", Q.trim(in.notes()));
        }
        data.put("updatedBy", TenantContext.userId());
        db.update(QualitySpecification.class, id, data);
        if (in.parameters() != null) {
            db.jdbc().sql("DELETE FROM \"QualitySpecParameter\" WHERE \"specificationId\" = ? AND \"organizationId\" = ?")
                    .params(id, QualityDb.org()).update();
            writeParameters(id, in.parameters());
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", null, Obj.of("code", s.get("code"))));
        return get(id);
    }

    /** Activate a draft: it becomes the code's active version, the previous active one is superseded. */
    @Transactional
    public Map<String, Object> activate(String id) {
        Map<String, Object> s = db.require(QualitySpecification.class, id, true, "Specification not found");
        requireDraft(s);
        List<Map<String, Object>> params = parameterRows(id);
        if (params.isEmpty()) {
            throw ApiException.badRequest("A specification needs at least one parameter before it can be activated.");
        }
        for (Map<String, Object> p : params) {
            if ("sieve".equals(p.get("kind")) && toParameter(p).envelope().isEmpty()) {
                throw ApiException.badRequest("Sieve parameter " + p.get("code") + " needs a grading envelope.");
            }
        }
        db.jdbc().sql("UPDATE \"QualitySpecification\" SET status = 'superseded', \"updatedAt\" = " + QualityDb.NOW
                + " WHERE \"organizationId\" = ? AND code = ? AND status = 'active' AND \"deletedAt\" IS NULL")
                .params(QualityDb.org(), s.get("code")).update();
        db.update(QualitySpecification.class, id, Obj.of("status", "active", "activatedAt", Instant.now(),
                "activatedBy", TenantContext.userId(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "approve", null, Obj.of("op", "activate", "code", s.get("code"),
                "version", s.get("version"))));
        return get(id);
    }

    /** New draft version (max + 1) copied from {@code id}. */
    @Transactional
    public Map<String, Object> revise(String id) {
        Map<String, Object> s = db.require(QualitySpecification.class, id, true, "Specification not found");
        if ("draft".equals(s.get("status"))) {
            throw ApiException.badRequest("Version " + s.get("version") + " is still a draft; edit it instead.");
        }
        long drafts = db.jdbc().sql("SELECT count(*) FROM \"QualitySpecification\" WHERE \"organizationId\" = ?"
                + " AND code = ? AND status = 'draft' AND \"deletedAt\" IS NULL").params(QualityDb.org(), s.get("code"))
                .query(Long.class).single();
        if (drafts > 0) {
            throw ApiException.conflict("Specification " + s.get("code") + " already has a draft version.");
        }
        int next = db.jdbc().sql("SELECT COALESCE(MAX(version), 0) + 1 FROM \"QualitySpecification\" WHERE"
                + " \"organizationId\" = ? AND code = ?").params(QualityDb.org(), s.get("code")).query(Integer.class)
                .single();
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : List.of("code", "name", "productId", "grade", "standard", "notes")) {
            data.put(k, s.get(k));
        }
        data.put("version", next);
        data.put("status", "draft");
        data.put("revisedFromId", id);
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        String newId = Q.str(db.insert(QualitySpecification.class, data), "id");
        for (Map<String, Object> p : parameterRows(id)) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (String k : List.of("code", "name", "kind", "unit", "testMethod", "minValue", "maxValue", "targetValue",
                    "decimals", "expectedText", "isCritical", "sortOrder")) {
                copy.put(k, p.get(k));
            }
            copy.put("sieveEnvelope", EntityJson.toJson(p.get("sieveEnvelope")));
            copy.put("specificationId", newId);
            db.insert(QualitySpecParameter.class, copy);
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, newId, "create", null, Obj.of("op", "revise", "code", s.get("code"),
                "version", next, "from", s.get("version"))));
        return get(newId);
    }

    @Transactional
    public Map<String, Object> archive(String id) {
        Map<String, Object> s = db.require(QualitySpecification.class, id, true, "Specification not found");
        if ("archived".equals(s.get("status"))) {
            return get(id);
        }
        db.update(QualitySpecification.class, id, Obj.of("status", "archived", "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", Obj.of("status", s.get("status")),
                Obj.of("status", "archived")));
        return get(id);
    }

    private void writeParameters(String specId, List<SpecParameterInput> params) {
        Set<String> codes = new HashSet<>();
        int order = 0;
        for (SpecParameterInput p : params) {
            String code = p.code().trim().toUpperCase(java.util.Locale.ROOT);
            if (!codes.add(code)) {
                throw ApiException.badRequest("Parameter " + code + " is listed twice.");
            }
            String kind = p.kind() == null ? "numeric" : p.kind();
            if (p.minValue() != null && p.maxValue() != null && p.minValue().compareTo(p.maxValue()) > 0) {
                throw ApiException.badRequest("Parameter " + code + ": minimum is above maximum.");
            }
            List<Map<String, Object>> envelope = new ArrayList<>();
            if ("sieve".equals(kind)) {
                Set<BigDecimal> sieves = new HashSet<>();
                for (SievePointInput e : p.sieveEnvelope() == null ? List.<SievePointInput>of() : p.sieveEnvelope()) {
                    if (!sieves.add(e.sieveMm().stripTrailingZeros())) {
                        throw ApiException.badRequest("Parameter " + code + ": sieve " + SpecEvaluator.plain(e.sieveMm())
                                + " mm is listed twice.");
                    }
                    if (e.minPassingPct() != null && e.maxPassingPct() != null
                            && e.minPassingPct().compareTo(e.maxPassingPct()) > 0) {
                        throw ApiException.badRequest("Parameter " + code + ": sieve " + SpecEvaluator.plain(e.sieveMm())
                                + " mm minimum passing is above its maximum.");
                    }
                    envelope.add(Obj.of("sieveMm", e.sieveMm(), "minPassingPct", e.minPassingPct(), "maxPassingPct",
                            e.maxPassingPct()));
                }
                envelope.sort((a, b) -> ((BigDecimal) b.get("sieveMm")).compareTo((BigDecimal) a.get("sieveMm")));
            } else if (p.sieveEnvelope() != null && !p.sieveEnvelope().isEmpty()) {
                throw ApiException.badRequest("Parameter " + code + ": a grading envelope needs kind sieve.");
            }
            if ("boolean".equals(kind) && p.expectedText() != null
                    && !List.of("true", "false").contains(p.expectedText().trim().toLowerCase(java.util.Locale.ROOT))) {
                throw ApiException.badRequest("Parameter " + code + ": expected value must be true or false.");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("specificationId", specId);
            data.put("code", code);
            data.put("name", p.name().trim());
            data.put("kind", kind);
            data.put("unit", Q.trim(p.unit()));
            data.put("testMethod", Q.trim(p.testMethod()));
            data.put("minValue", "numeric".equals(kind) ? p.minValue() : null);
            data.put("maxValue", "numeric".equals(kind) ? p.maxValue() : null);
            data.put("targetValue", "numeric".equals(kind) ? p.targetValue() : null);
            data.put("decimals", p.decimals() == null ? 2 : p.decimals());
            data.put("expectedText", Q.trim(p.expectedText()));
            data.put("sieveEnvelope", EntityJson.toJson(envelope));
            data.put("isCritical", p.isCritical() == null || p.isCritical());
            data.put("sortOrder", p.sortOrder() != null ? p.sortOrder() : order);
            db.insert(QualitySpecParameter.class, data);
            order++;
        }
    }

    private static void requireDraft(Map<String, Object> s) {
        if (!"draft".equals(s.get("status"))) {
            throw ApiException.badRequest("Only a draft specification can be edited (version " + s.get("version")
                    + " is " + s.get("status") + ").");
        }
    }

    void requireProduct(String productId) {
        if (Q.has(productId) && !db.exists("Product", productId, true)) {
            throw ApiException.notFound("Product " + productId + " not found");
        }
    }

    String productName(String productId) {
        if (productId == null) {
            return null;
        }
        return db.jdbc().sql("SELECT name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, QualityDb.org()).query(String.class).optional().orElse(null);
    }
}
