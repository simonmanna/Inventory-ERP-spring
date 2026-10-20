package com.inventoryerp.documents.application;

import com.inventoryerp.documents.domain.DocumentLifecycle;
import com.inventoryerp.documents.domain.DocumentTransition;
import com.inventoryerp.documents.domain.DocumentTypeDef;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DMS registry API (port of {@code document-type-defs.service.ts} + {@code document-lifecycles.service.ts}): the
 * effective type list for an organization, lookups, admin create / update / soft delete, categories and lifecycles
 * with their transitions.
 */
@Service
public class DocumentTypeDefsService {

    private static final Set<String> JSON_COLUMNS = Set.of("approvalPolicy", "postingPolicy", "paymentPolicy",
            "cancellationPolicy", "snapshotPolicy", "printProfile", "businessEffects", "security");

    private final JdbcClient jdbc;
    private final DmsStore store;
    private final DmsTypeResolver resolver;

    public DocumentTypeDefsService(JdbcClient jdbc, DmsStore store, DmsTypeResolver resolver) {
        this.jdbc = jdbc;
        this.store = store;
        this.resolver = resolver;
    }

    /** Global registry + per-org activation rows resolved for one org (ADR O2). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEffective(String organizationId) {
        Map<String, Map<String, Object>> overrides = new HashMap<>();
        for (Map<String, Object> o : jdbc.sql("SELECT * FROM \"OrganizationDocumentType\" WHERE \"organizationId\" = ?")
                .param(organizationId).query().listOfRows()) {
            overrides.put((String) o.get("documentTypeId"), o);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> raw : jdbc.sql("SELECT * FROM \"DocumentTypeDef\" WHERE \"isActive\" = true ORDER BY"
                + " code ASC").query().listOfRows()) {
            Map<String, Object> d = SqlRows.as(DocumentTypeDef.class, raw);
            Map<String, Object> o = overrides.get((String) d.get("id"));
            Map<String, Object> e = new LinkedHashMap<>();
            for (String k : List.of("id", "code", "name", "description", "category", "isSystem")) {
                e.put(k, d.get(k));
            }
            e.put("isActive", o == null || Boolean.TRUE.equals(o.get("isActive")));
            e.put("configOverrides", o == null ? null : SqlRows.value(o.get("configOverrides")));
            for (String k : List.of("numberingKey", "numberingPrefix", "numberingPadding", "editPolicy", "snapshotPolicy",
                    "printProfile", "businessEffects", "security", "isFinancial", "isInventoryRelevant",
                    "requiresPosting", "approvalPolicy", "postingPolicy", "paymentPolicy", "cancellationPolicy")) {
                e.put(k, d.get(k));
            }
            out.add(e);
        }
        return out;
    }

    /** Active type by code; 404 {@code Unknown document type code: x}. */
    @Transactional(readOnly = true)
    public Map<String, Object> getByCode(String code) {
        Map<String, Object> def = store.rawDefByCode(code);
        if (def == null || !Boolean.TRUE.equals(def.get("isActive"))) {
            throw ApiException.notFound("Unknown document type code: " + code);
        }
        return SqlRows.as(DocumentTypeDef.class, def);
    }

    /** Admin create: global type + activation for the creating org. {@code config} keys spread into the row. */
    @Transactional
    public Map<String, Object> create(String orgId, String code, String name, String category, String lifecycleId,
            Map<String, Object> config) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("code", code);
        data.put("name", name);
        data.put("description", "");
        data.put("category", category != null ? category : "custom");
        if (lifecycleId != null) {
            data.put("lifecycleId", lifecycleId);
        }
        if (config != null) {
            data.putAll(config);
        }
        insertDef(data);
        jdbc.sql("INSERT INTO \"OrganizationDocumentType\" (id, \"organizationId\", \"documentTypeId\", \"isActive\","
                        + " \"updatedAt\") VALUES (?, ?, ?, true, " + DmsStore.NOW + ")")
                .params(UUID.randomUUID().toString(), orgId, id).update();
        resolver.refresh();
        return SqlRows.as(DocumentTypeDef.class, store.rawDef(id));
    }

    /** Admin update by code (body keys written as columns). */
    @Transactional
    public Map<String, Object> updateByCode(String code, Map<String, Object> patch) {
        Map<String, Object> def = store.rawDefByCode(code);
        if (def == null) {
            throw ApiException.notFound("Unknown document type code: " + code);
        }
        if (!patch.isEmpty()) {
            List<String> sets = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            patch.forEach((k, v) -> {
                requireColumn(k);
                sets.add("\"" + k + "\" = " + (JSON_COLUMNS.contains(k) ? "?::jsonb" : "?"));
                params.add(JSON_COLUMNS.contains(k) ? store.toJson(v) : v);
            });
            sets.add("\"updatedAt\" = " + DmsStore.NOW);
            params.add(def.get("id"));
            jdbc.sql("UPDATE \"DocumentTypeDef\" SET " + String.join(", ", sets) + " WHERE id = ?").params(params)
                    .update();
        }
        return SqlRows.as(DocumentTypeDef.class, store.rawDef(def.get("id")));
    }

    /** Soft delete by code ({@code isActive = false}); system types are protected. */
    @Transactional
    public Map<String, Object> removeByCode(String code) {
        Map<String, Object> def = store.rawDefByCode(code);
        if (def == null) {
            throw ApiException.notFound("Unknown document type code: " + code);
        }
        if (Boolean.TRUE.equals(def.get("isSystem"))) {
            throw ApiException.badRequest("System document types cannot be deleted");
        }
        jdbc.sql("UPDATE \"DocumentTypeDef\" SET \"isActive\" = false, \"updatedAt\" = " + DmsStore.NOW + " WHERE id = ?")
                .param(def.get("id")).update();
        return SqlRows.as(DocumentTypeDef.class, store.rawDef(def.get("id")));
    }

    /** Distinct categories, ascending. */
    @Transactional(readOnly = true)
    public List<String> listCategories() {
        return jdbc.sql("SELECT DISTINCT category FROM \"DocumentTypeDef\" ORDER BY category ASC").query(String.class)
                .list();
    }

    /** Every lifecycle with its transitions (fromState, action ascending). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLifecycles() {
        List<Map<String, Object>> out = SqlRows.list(DocumentLifecycle.class, jdbc.sql("SELECT * FROM"
                + " \"DocumentLifecycle\" ORDER BY code ASC"));
        out.forEach(this::withTransitions);
        return out;
    }

    /** One lifecycle by code (null when unknown — Prisma findUnique). */
    @Transactional(readOnly = true)
    public Map<String, Object> lifecycle(String code) {
        Map<String, Object> lc = SqlRows.one(DocumentLifecycle.class, jdbc.sql("SELECT * FROM \"DocumentLifecycle\""
                + " WHERE code = ?").param(code));
        return lc == null ? null : withTransitions(lc);
    }

    private Map<String, Object> withTransitions(Map<String, Object> lc) {
        lc.put("transitions", SqlRows.list(DocumentTransition.class, jdbc.sql("SELECT * FROM \"DocumentTransition\""
                + " WHERE \"lifecycleId\" = ? ORDER BY \"fromState\" ASC, action ASC").param(lc.get("id"))));
        return lc;
    }

    private void requireColumn(String k) {
        if (!EntityJson.columnNames(DocumentTypeDef.class).contains(k)) {
            // Prisma rejects unknown data keys (PrismaClientValidationError → 500).
            throw new IllegalStateException("Unknown argument `" + k + "` for DocumentTypeDef");
        }
    }

    private void insertDef(Map<String, Object> data) {
        List<String> cols = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        data.forEach((k, v) -> {
            requireColumn(k);
            cols.add("\"" + k + "\"");
            marks.add(JSON_COLUMNS.contains(k) ? "?::jsonb" : "?");
            params.add(JSON_COLUMNS.contains(k) ? store.toJson(v) : v);
        });
        cols.add("\"updatedAt\"");
        marks.add(DmsStore.NOW);
        jdbc.sql("INSERT INTO \"DocumentTypeDef\" (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", marks)
                + ")").params(params).update();
    }
}
