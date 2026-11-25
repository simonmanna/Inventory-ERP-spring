package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.qualitycontrol.application.QcCommands.TemplateInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.TemplatePatch;
import com.inventoryerp.qualitycontrol.domain.QcWorksheetTemplate;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Versioned worksheet templates (ADR-S087 §10). Each row is one version; versions of a template share
 * {@code templateKey} and exactly one is {@code isLatest}. Editing the latest version in place is allowed only while no
 * check references it; otherwise the edit writes version n+1 and the referenced version stays as the checks pinned it.
 */
@Service
public class QcWorksheetService {

    private final QcDb db;
    private final AuditService audit;

    public QcWorksheetService(QcDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    private static final String USAGE = "(SELECT count(*) FROM \"QcCheck\" c WHERE c.\"worksheetTemplateId\" = t.id)";

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Boolean active) {
        String where = " WHERE t.\"organizationId\" = ? AND t.\"isLatest\"" + (active != null ? " AND t.\"isActive\" = ?" : "");
        List<Object> params = active != null ? List.of(QcDb.org(), active) : List.of(QcDb.org());
        return SqlRows.list(db.jdbc().sql("SELECT t.*, " + USAGE + " AS \"checkCount\", (SELECT count(*) FROM"
                + " \"QcWorksheetTemplate\" v WHERE v.\"organizationId\" = t.\"organizationId\" AND v.\"templateKey\" ="
                + " t.\"templateKey\") AS \"versionCount\" FROM \"QcWorksheetTemplate\" t" + where
                + " ORDER BY lower(t.name)").params(params));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> t = db.require(QcWorksheetTemplate.class, id, false, "Worksheet template not found");
        t.put("versions", SqlRows.list(db.jdbc().sql("SELECT t.id, t.version, t.name, t.\"isLatest\", t.\"isActive\","
                + " t.\"createdAt\", t.\"createdBy\", " + USAGE + " AS \"checkCount\" FROM \"QcWorksheetTemplate\" t"
                + " WHERE t.\"organizationId\" = ? AND t.\"templateKey\" = ? ORDER BY t.version DESC")
                .params(QcDb.org(), t.get("templateKey"))));
        return t;
    }

    @Transactional
    public Map<String, Object> create(TemplateInput in) {
        JsonNode fields = tree(in.fields());
        WorksheetEvaluator.validateDefinition(fields);
        Map<String, Object> row = db.insert(QcWorksheetTemplate.class, Obj.of("templateKey", UUID.randomUUID().toString(),
                "version", 1, "name", in.name().trim(), "description", QcUtil.trim(in.description()), "fields",
                EntityJson.toJson(fields), "isLatest", true, "isActive", true, "createdBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("QcWorksheetTemplate", QcUtil.str(row, "id"), "create", null,
                Obj.of("name", row.get("name"), "version", 1)));
        return get(QcUtil.str(row, "id"));
    }

    @Transactional
    public Map<String, Object> update(String id, TemplatePatch in) {
        Map<String, Object> cur = db.require(QcWorksheetTemplate.class, id, true, "Worksheet template not found");
        if (!QcUtil.bool(cur, "isLatest")) {
            throw ApiException.badRequest("Only the latest version of a worksheet template can be edited.");
        }
        JsonNode fields = null;
        if (in.fields() != null) {
            fields = tree(in.fields());
            WorksheetEvaluator.validateDefinition(fields);
        }
        boolean content = in.name() != null || in.description() != null || fields != null;
        String resultId = id;
        if (content && used(id)) {
            // The current version is pinned by checks: write version n+1.
            int version = QcUtil.integer(cur, "version") + 1;
            db.update(QcWorksheetTemplate.class, id, Obj.of("isLatest", false));
            Map<String, Object> next = db.insert(QcWorksheetTemplate.class, Obj.of("templateKey", cur.get("templateKey"),
                    "version", version, "name", in.name() != null ? in.name().trim() : cur.get("name"),
                    "description", in.description() != null ? QcUtil.trim(in.description()) : cur.get("description"),
                    "fields", fields != null ? EntityJson.toJson(fields) : EntityJson.toJson(cur.get("fields")),
                    "isLatest", true, "isActive", in.isActive() != null ? in.isActive() : cur.get("isActive"),
                    "createdBy", TenantContext.userId()));
            resultId = QcUtil.str(next, "id");
            audit.recordInTx(AuditInput.of("QcWorksheetTemplate", resultId, "create", Obj.of("id", id,
                    "version", version - 1), Obj.of("op", "new_version", "version", version)));
        } else {
            Map<String, Object> data = new LinkedHashMap<>();
            if (in.name() != null) {
                data.put("name", in.name().trim());
            }
            if (in.description() != null) {
                data.put("description", QcUtil.trim(in.description()));
            }
            if (fields != null) {
                data.put("fields", EntityJson.toJson(fields));
            }
            if (in.isActive() != null) {
                data.put("isActive", in.isActive());
            }
            if (!data.isEmpty()) {
                db.update(QcWorksheetTemplate.class, id, data);
                audit.recordInTx(AuditInput.of("QcWorksheetTemplate", id, "update", null, data.keySet()));
            }
        }
        return get(resultId);
    }

    /** The latest active version of a template family, or 400. */
    Map<String, Object> latestActive(String templateKey) {
        List<Map<String, Object>> rows = db.list(QcWorksheetTemplate.class, " AND \"templateKey\" = ? AND \"isLatest\"",
                templateKey);
        if (rows.isEmpty() || !QcUtil.bool(rows.getFirst(), "isActive")) {
            throw ApiException.badRequest("Worksheet template is not active");
        }
        return rows.getFirst();
    }

    /** A template version by id, or 400. */
    Map<String, Object> requireVersion(String id) {
        Map<String, Object> row = db.byId(QcWorksheetTemplate.class, id, false);
        if (row == null) {
            throw ApiException.badRequest("Worksheet template " + id + " not found");
        }
        return row;
    }

    private boolean used(String id) {
        return db.jdbc().sql("SELECT count(*) FROM \"QcCheck\" WHERE \"worksheetTemplateId\" = ? AND \"organizationId\" = ?")
                .params(id, QcDb.org()).query(Long.class).single() > 0;
    }

    static JsonNode tree(Object value) {
        return EntityJson.tree(EntityJson.toJson(value));
    }
}
