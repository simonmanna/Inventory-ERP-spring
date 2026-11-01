package com.inventoryerp.customization.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom objects (ADR-S029 §2): organization-defined record types whose fields are custom-field definitions on entity
 * {@code x_<key>}, and their records with values validated by {@link CustomFieldService#validate}.
 */
@Service
public class CustomObjectService {

    public static final int MAX_OBJECTS = 50;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");

    /** Definition input (update: nulls keep the stored value). */
    public record ObjectInput(String key, String label, String pluralLabel, String icon, String displayField,
            String description, Boolean isActive) {}

    private final JdbcClient jdbc;
    private final CustomFieldService fields;
    private final AuditService audit;

    public CustomObjectService(JdbcClient jdbc, CustomFieldService fields, AuditService audit) {
        this.jdbc = jdbc;
        this.fields = fields;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT d.*, (SELECT count(*) FROM \"CustomObjectRecord\" r WHERE r.\"objectId\" ="
                + " d.id AND r.\"deletedAt\" IS NULL) AS \"recordCount\" FROM \"CustomObjectDefinition\" d WHERE"
                + " d.\"organizationId\" = ? ORDER BY d.label").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String key) {
        Map<String, Object> d = definition(key);
        d.put("fields", fields.listDefinitions("x_" + key, false));
        return d;
    }

    @Transactional
    public Map<String, Object> create(ObjectInput in) {
        if (in.key() == null || !KEY.matcher(in.key()).matches()) {
            throw ApiException.badRequest("key must be 2 to 40 lowercase letters, digits or underscores, starting with a"
                    + " letter");
        }
        if (jdbc.sql("SELECT count(*) FROM \"CustomObjectDefinition\" WHERE \"organizationId\" = ?").param(org())
                .query(Long.class).single() >= MAX_OBJECTS) {
            throw ApiException.badRequest("An organization can have at most " + MAX_OBJECTS + " custom objects");
        }
        if (jdbc.sql("SELECT count(*) FROM \"CustomObjectDefinition\" WHERE \"organizationId\" = ? AND key = ?")
                .params(org(), in.key()).query(Long.class).single() > 0) {
            throw ApiException.conflict("A custom object with key '" + in.key() + "' already exists");
        }
        String label = required(in.label(), "label");
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CustomObjectDefinition\" (id, \"organizationId\", key, label, \"pluralLabel\", icon,"
                + " \"displayField\", description, \"isActive\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.key(), label, in.pluralLabel() == null || in.pluralLabel().isBlank() ? label + "s"
                        : in.pluralLabel().trim(), in.icon(), in.displayField(), in.description(),
                        in.isActive() == null || in.isActive(), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("CustomObjectDefinition", id, "create", null, Obj.of("key", in.key())));
        return get(in.key());
    }

    @Transactional
    public Map<String, Object> update(String key, ObjectInput in) {
        Map<String, Object> before = definition(key);
        if (in.displayField() != null && !in.displayField().isBlank() && fields.fields(org(), "x_" + key).stream()
                .noneMatch(f -> f.key().equals(in.displayField()))) {
            throw ApiException.badRequest("displayField must be one of the object's fields");
        }
        jdbc.sql("UPDATE \"CustomObjectDefinition\" SET label = ?, \"pluralLabel\" = ?, icon = ?, \"displayField\" = ?,"
                + " description = ?, \"isActive\" = ?, \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(in.label() == null ? before.get("label") : required(in.label(), "label"),
                        in.pluralLabel() == null ? before.get("pluralLabel") : required(in.pluralLabel(), "pluralLabel"),
                        in.icon() == null ? before.get("icon") : in.icon(),
                        in.displayField() == null ? before.get("displayField")
                                : in.displayField().isBlank() ? null : in.displayField(),
                        in.description() == null ? before.get("description") : in.description(),
                        in.isActive() == null ? before.get("isActive") : in.isActive(), before.get("id"), org())
                .update();
        if (in.displayField() != null) {
            retitle((String) before.get("id"), in.displayField().isBlank() ? null : in.displayField());
        }
        audit.recordInTx(AuditInput.of("CustomObjectDefinition", (String) before.get("id"), "update", before,
                definition(key)));
        return get(key);
    }

    private void retitle(String objectId, String displayField) {
        jdbc.sql("UPDATE \"CustomObjectRecord\" SET title = CASE WHEN ?::text IS NULL THEN NULL ELSE \"values\" ->> ?"
                + " END WHERE \"objectId\" = ? AND \"organizationId\" = ?").params(displayField, displayField, objectId,
                        org()).update();
    }

    private Map<String, Object> definition(String key) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT * FROM \"CustomObjectDefinition\" WHERE \"organizationId\" ="
                + " ? AND key = ?").params(org(), key));
        if (d == null) {
            throw ApiException.notFound("Custom object not found");
        }
        return d;
    }

    // ---- records --------------------------------------------------------------------------------------------------

    /** {@code filters}: field key → exact value (string comparison on the stored JSON). */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> records(String key, PageQuery q, Map<String, String> filters) {
        Map<String, Object> d = definition(key);
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = ? AND \"objectId\" = ? AND \"deletedAt\" IS"
                + " NULL");
        List<Object> params = new ArrayList<>(List.of(org(), d.get("id")));
        if (q.search() != null && !q.search().isBlank()) {
            where.append(" AND (title ILIKE ? OR \"values\"::text ILIKE ?)");
            String like = "%" + q.search().trim() + "%";
            params.add(like);
            params.add(like);
        }
        for (Map.Entry<String, String> f : filters.entrySet()) {
            where.append(" AND \"values\" ->> ? = ?");
            params.add(f.getKey());
            params.add(f.getValue());
        }
        long total = jdbc.sql("SELECT count(*) FROM \"CustomObjectRecord\"" + where).params(params).query(Long.class)
                .single();
        List<Object> page = new ArrayList<>(params);
        page.add(q.pageSizeOrDefault());
        page.add(q.offset());
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM \"CustomObjectRecord\"" + where
                + " ORDER BY \"createdAt\" DESC, id LIMIT ? OFFSET ?").params(page));
        return PageResult.of(rows, q, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> record(String key, String id) {
        Map<String, Object> d = definition(key);
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"CustomObjectRecord\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"objectId\" = ? AND \"deletedAt\" IS NULL").params(id, org(), d.get("id")));
        if (r == null) {
            throw ApiException.notFound("Record not found");
        }
        return r;
    }

    @Transactional
    public Map<String, Object> createRecord(String key, Map<String, Object> values) {
        Map<String, Object> d = definition(key);
        if (!Boolean.TRUE.equals(d.get("isActive"))) {
            throw ApiException.badRequest("The custom object is inactive");
        }
        Map<String, Object> clean = fields.validate(org(), "x_" + key, Map.of(), values, false);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CustomObjectRecord\" (id, \"organizationId\", \"objectId\", title, \"values\","
                + " \"createdBy\", \"updatedBy\") VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)").params(id, org(), d.get("id"),
                        title(d, clean), EntityJson.toJson(clean), TenantContext.userId(), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("CustomObjectRecord", id, "create", null, clean));
        return record(key, id);
    }

    @Transactional
    public Map<String, Object> updateRecord(String key, String id, Map<String, Object> values) {
        Map<String, Object> d = definition(key);
        Map<String, Object> before = record(key, id);
        Map<String, Object> clean = fields.validate(org(), "x_" + key, CustomFieldService.asMap(before.get("values")),
                values, true);
        jdbc.sql("UPDATE \"CustomObjectRecord\" SET \"values\" = ?::jsonb, title = ?, \"updatedBy\" = ?, \"updatedAt\" ="
                + " now() WHERE id = ? AND \"organizationId\" = ?").params(EntityJson.toJson(clean), title(d, clean),
                        TenantContext.userId(), id, org()).update();
        audit.recordInTx(AuditInput.of("CustomObjectRecord", id, "update", before.get("values"), clean));
        return record(key, id);
    }

    @Transactional
    public Map<String, Object> deleteRecord(String key, String id) {
        record(key, id);
        jdbc.sql("UPDATE \"CustomObjectRecord\" SET \"deletedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).update();
        audit.recordInTx(AuditInput.of("CustomObjectRecord", id, "delete"));
        return Obj.of("id", id, "deleted", true);
    }

    private static String title(Map<String, Object> d, Map<String, Object> values) {
        Object field = d.get("displayField");
        Object v = field == null ? null : values.get(String.valueOf(field));
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static String required(String v, String name) {
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest(name + " should not be empty");
        }
        return v.trim();
    }
}
