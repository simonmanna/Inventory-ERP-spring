package com.inventoryerp.reporting.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportRequest;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved report views: a named set of filters for one report. Private to the owner, or shared with everyone who may
 * run the report ({@code report_library:manage} to share). A view keeps a period preset, so it always means the
 * current period.
 */
@Service
public class SavedViewService {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ReportEngine engine;

    public SavedViewService(JdbcClient jdbc, JsonMapper json, ReportEngine engine) {
        this.jdbc = jdbc;
        this.json = json;
        this.engine = engine;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String reportId) {
        engine.definition(reportId);
        return SqlRows.list(jdbc.sql("SELECT id, \"reportId\", name, params, \"ownerId\", shared, \"createdAt\","
                + " \"updatedAt\" FROM \"ReportSavedView\" WHERE \"organizationId\" = ? AND \"reportId\" = ?"
                + " AND (shared OR \"ownerId\" = ?) ORDER BY shared, name").params(org(), reportId,
                TenantContext.userId()));
    }

    @Transactional
    public Map<String, Object> create(String reportId, String name, Map<String, Object> params, boolean shared) {
        ReportDefinition def = engine.definition(reportId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        if (shared && !TenantContext.has(Permissions.REPORT_LIBRARY__MANAGE)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.REPORT_LIBRARY__MANAGE);
        }
        ReportRequest request = engine.fromMap(params);
        ReportEngine.validateFilters(def, request);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ReportSavedView\" (id, \"organizationId\", \"reportId\", name, params, \"ownerId\","
                + " shared) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)").params(id, org(), reportId, name.trim(),
                json.writeValueAsString(request), TenantContext.userId(), shared).update();
        return SqlRows.one(jdbc.sql("SELECT * FROM \"ReportSavedView\" WHERE id = ?").param(id));
    }

    @Transactional
    public void delete(String id) {
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT \"ownerId\" FROM \"ReportSavedView\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (v == null) {
            throw ApiException.notFound("Saved view not found");
        }
        if (!String.valueOf(TenantContext.userId()).equals(v.get("ownerId"))
                && !TenantContext.has(Permissions.REPORT_LIBRARY__MANAGE)) {
            throw ApiException.forbidden("Only the owner can delete this view");
        }
        jdbc.sql("DELETE FROM \"ReportSavedView\" WHERE id = ?").param(id).update();
    }
}
