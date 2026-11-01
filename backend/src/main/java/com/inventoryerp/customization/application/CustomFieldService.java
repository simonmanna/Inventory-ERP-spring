package com.inventoryerp.customization.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom fields (ADR-S029 §1): definitions per entity and validated values per record, kept in a side table so core
 * payloads never change. Custom-object records (§2) validate their values here too.
 */
@Service
public class CustomFieldService {

    public static final Set<String> TYPES = Set.of("text", "textarea", "number", "decimal", "date", "boolean", "select",
            "multiselect", "reference");
    public static final int MAX_FIELDS_PER_ENTITY = 100;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,39}$");
    private static final int MAX_TEXT = 2000;
    private static final int MAX_TEXTAREA = 20000;

    /** Definition input (create: all required fields; update: nulls keep the stored value). */
    public record DefinitionInput(String entity, String key, String label, String type, Map<String, Object> options,
            Boolean required, Object defaultValue, String helpText, Integer sortOrder, Boolean showInList,
            Boolean isActive) {}

    /** A definition as used for validation. */
    public record Field(String key, String label, String type, Map<String, Object> options, boolean required,
            Object defaultValue, boolean active) {}

    private final JdbcClient jdbc;
    private final CustomEntities entities;
    private final AuditService audit;

    public CustomFieldService(JdbcClient jdbc, CustomEntities entities, AuditService audit) {
        this.jdbc = jdbc;
        this.entities = entities;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ---- definitions ----------------------------------------------------------------------------------------------

    /** {@code GET /custom-fields/entities}: core entities and custom objects with their field counts. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> entityCatalog() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT entity, count(*) AS n FROM \"CustomFieldDefinition\""
                + " WHERE \"organizationId\" = ? AND \"isActive\" GROUP BY entity").param(org()))) {
            counts.put((String) r.get("entity"), ((Number) r.get("n")).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        CustomEntities.CORE.values().forEach(e -> out.add(Obj.of("entity", e.key(), "label", e.label(), "custom", false,
                "fieldCount", counts.getOrDefault(e.key(), 0L))));
        for (Map<String, Object> o : SqlRows.list(jdbc.sql("SELECT key, label FROM \"CustomObjectDefinition\" WHERE"
                + " \"organizationId\" = ? AND \"isActive\" ORDER BY label").param(org()))) {
            String entity = "x_" + o.get("key");
            out.add(Obj.of("entity", entity, "label", o.get("label"), "custom", true, "fieldCount",
                    counts.getOrDefault(entity, 0L)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDefinitions(String entity, boolean includeInactive) {
        entities.assertEntity(org(), entity);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"CustomFieldDefinition\" WHERE \"organizationId\" = ? AND entity = ?"
                + (includeInactive ? "" : " AND \"isActive\"") + " ORDER BY \"sortOrder\", label").params(org(), entity));
    }

    /** Active fields of {@code entity} in order. */
    public List<Field> fields(String org, String entity) {
        List<Field> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT key, label, type, options, required,"
                + " \"defaultValue\", \"isActive\" FROM \"CustomFieldDefinition\" WHERE \"organizationId\" = ? AND"
                + " entity = ? ORDER BY \"sortOrder\", label").params(org, entity))) {
            out.add(new Field((String) r.get("key"), (String) r.get("label"), (String) r.get("type"),
                    asMap(r.get("options")), Boolean.TRUE.equals(r.get("required")), plain(r.get("defaultValue")),
                    Boolean.TRUE.equals(r.get("isActive"))));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createDefinition(DefinitionInput in) {
        entities.assertEntity(org(), in.entity());
        if (in.key() == null || !KEY.matcher(in.key()).matches()) {
            throw ApiException.badRequest("key must start with a lowercase letter and contain only letters, digits and"
                    + " underscores (at most 40)");
        }
        if (!TYPES.contains(in.type())) {
            throw ApiException.badRequest("type must be one of the following values: " + String.join(", ",
                    List.of("text", "textarea", "number", "decimal", "date", "boolean", "select", "multiselect",
                            "reference")));
        }
        long existing = jdbc.sql("SELECT count(*) FROM \"CustomFieldDefinition\" WHERE \"organizationId\" = ? AND"
                + " entity = ?").params(org(), in.entity()).query(Long.class).single();
        if (existing >= MAX_FIELDS_PER_ENTITY) {
            throw ApiException.badRequest("An entity can have at most " + MAX_FIELDS_PER_ENTITY + " custom fields");
        }
        if (jdbc.sql("SELECT count(*) FROM \"CustomFieldDefinition\" WHERE \"organizationId\" = ? AND entity = ? AND"
                + " key = ?").params(org(), in.entity(), in.key()).query(Long.class).single() > 0) {
            throw ApiException.conflict("A custom field with key '" + in.key() + "' already exists on " + in.entity());
        }
        Map<String, Object> options = normalizeOptions(in.type(), in.options());
        Field probe = new Field(in.key(), in.label(), in.type(), options, false, null, true);
        Object defaultValue = in.defaultValue() == null ? null : coerce(probe, in.defaultValue());
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CustomFieldDefinition\" (id, \"organizationId\", entity, key, label, type, options,"
                + " required, \"defaultValue\", \"helpText\", \"sortOrder\", \"showInList\", \"isActive\", \"createdBy\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?)")
                .params(id, org(), in.entity(), in.key(), label(in.label()), in.type(), EntityJson.toJson(options),
                        Boolean.TRUE.equals(in.required()), defaultValue == null ? null : EntityJson.toJson(defaultValue),
                        in.helpText(), in.sortOrder() == null ? 0 : in.sortOrder(), Boolean.TRUE.equals(in.showInList()),
                        in.isActive() == null || in.isActive(), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("CustomFieldDefinition", id, "create", null, Obj.of("entity", in.entity(),
                "key", in.key(), "type", in.type())));
        return definition(id);
    }

    @Transactional
    public Map<String, Object> updateDefinition(String id, DefinitionInput in) {
        Map<String, Object> before = definition(id);
        String type = in.type() == null ? (String) before.get("type") : in.type();
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("Unknown field type '" + type + "'");
        }
        if (!type.equals(before.get("type")) && hasValues((String) before.get("entity"), (String) before.get("key"))) {
            throw ApiException.badRequest("The type of a field with values cannot change");
        }
        Map<String, Object> options = in.options() == null && type.equals(before.get("type"))
                ? asMap(before.get("options")) : normalizeOptions(type, in.options());
        Field probe = new Field((String) before.get("key"), (String) before.get("label"), type, options, false, null,
                true);
        Object defaultValue = in.defaultValue() == null ? before.get("defaultValue") : coerce(probe, in.defaultValue());
        jdbc.sql("UPDATE \"CustomFieldDefinition\" SET label = ?, type = ?, options = ?::jsonb, required = ?,"
                + " \"defaultValue\" = ?::jsonb, \"helpText\" = ?, \"sortOrder\" = ?, \"showInList\" = ?, \"isActive\" = ?,"
                + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(in.label() == null ? before.get("label") : label(in.label()), type, EntityJson.toJson(options),
                        in.required() == null ? before.get("required") : in.required(),
                        defaultValue == null ? null : EntityJson.toJson(defaultValue),
                        in.helpText() == null ? before.get("helpText") : in.helpText(),
                        in.sortOrder() == null ? before.get("sortOrder") : in.sortOrder(),
                        in.showInList() == null ? before.get("showInList") : in.showInList(),
                        in.isActive() == null ? before.get("isActive") : in.isActive(), id, org()).update();
        Map<String, Object> after = definition(id);
        audit.recordInTx(AuditInput.of("CustomFieldDefinition", id, "update", before, after));
        return after;
    }

    /** Deactivate (values are kept, hidden). */
    @Transactional
    public Map<String, Object> deleteDefinition(String id) {
        definition(id);
        jdbc.sql("UPDATE \"CustomFieldDefinition\" SET \"isActive\" = false, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("CustomFieldDefinition", id, "delete"));
        return definition(id);
    }

    private Map<String, Object> definition(String id) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT * FROM \"CustomFieldDefinition\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (d == null) {
            throw ApiException.notFound("Custom field not found");
        }
        return d;
    }

    private boolean hasValues(String entity, String key) {
        if (entity.startsWith("x_")) {
            return jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"CustomObjectRecord\" r JOIN \"CustomObjectDefinition\" d ON"
                    + " d.id = r.\"objectId\" WHERE r.\"organizationId\" = ? AND d.key = ? AND"
                    + " jsonb_exists(r.\"values\", ?))").params(org(), entity.substring(2), key).query(Boolean.class)
                    .single();
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"CustomFieldValue\" WHERE \"organizationId\" = ? AND entity = ?"
                + " AND jsonb_exists(\"values\", ?))").params(org(), entity, key).query(Boolean.class).single();
    }

    // ---- values ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> getValues(String entity, String recordId) {
        assertRecord(entity, recordId);
        return Obj.of("entity", entity, "recordId", recordId, "values", visibleValues(org(), entity, recordId),
                "fields", listDefinitions(entity, false));
    }

    /** The record's values for active fields (print context, automation conditions). */
    public Map<String, Object> visibleValues(String org, String entity, String recordId) {
        Map<String, Object> stored = stored(org, entity, recordId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : fields(org, entity)) {
            if (f.active() && stored.containsKey(f.key())) {
                out.put(f.key(), stored.get(f.key()));
            }
        }
        return out;
    }

    private Map<String, Object> stored(String org, String entity, String recordId) {
        Object v = jdbc.sql("SELECT \"values\" FROM \"CustomFieldValue\" WHERE \"organizationId\" = ? AND entity = ? AND"
                + " \"recordId\" = ?").params(org, entity, recordId).query((rs, n) -> rs.getObject(1)).optional()
                .orElse(null);
        return v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(asMap(SqlRows.value(v)));
    }

    /** {@code PUT /custom-fields/values/:entity/:recordId}: merge {@code values} (a null value clears the key). */
    @Transactional
    public Map<String, Object> putValues(String entity, String recordId, Map<String, Object> values) {
        if (entity.startsWith("x_")) {
            throw ApiException.badRequest("Custom-object records keep their values on the record");
        }
        assertRecord(entity, recordId);
        Map<String, Object> before = stored(org(), entity, recordId);
        Map<String, Object> merged = validate(org(), entity, before, values, true);
        jdbc.sql("INSERT INTO \"CustomFieldValue\" (id, \"organizationId\", entity, \"recordId\", \"values\","
                + " \"updatedBy\") VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (\"organizationId\", entity, \"recordId\")"
                + " DO UPDATE SET \"values\" = EXCLUDED.\"values\", \"updatedBy\" = EXCLUDED.\"updatedBy\","
                + " \"updatedAt\" = now()").params(UUID.randomUUID().toString(), org(), entity, recordId,
                        EntityJson.toJson(merged), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("CustomFieldValue", recordId, "update", before, merged));
        return getValues(entity, recordId);
    }

    /** Automation {@code set_custom_field}: one key, validated, no audit user. */
    @Transactional
    public void setValue(String org, String entity, String recordId, String key, Object value) {
        Map<String, Object> before = stored(org, entity, recordId);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(key, value);
        Map<String, Object> merged = validate(org, entity, before, patch, true);
        jdbc.sql("INSERT INTO \"CustomFieldValue\" (id, \"organizationId\", entity, \"recordId\", \"values\")"
                + " VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT (\"organizationId\", entity, \"recordId\") DO UPDATE SET"
                + " \"values\" = EXCLUDED.\"values\", \"updatedAt\" = now()")
                .params(UUID.randomUUID().toString(), org, entity, recordId, EntityJson.toJson(merged)).update();
    }

    private void assertRecord(String entity, String recordId) {
        entities.assertEntity(org(), entity);
        if (!entities.exists(org(), entity, recordId)) {
            throw ApiException.notFound("Record not found");
        }
    }

    /**
     * Validate {@code patch} over {@code base} for {@code entity}: unknown or inactive keys are refused, values are
     * coerced, a null value removes the key, defaults fill missing keys on create, and required fields must be set.
     */
    public Map<String, Object> validate(String org, String entity, Map<String, Object> base, Map<String, Object> patch,
            boolean existing) {
        List<Field> fields = fields(org, entity);
        Map<String, Field> byKey = new LinkedHashMap<>();
        fields.forEach(f -> byKey.put(f.key(), f));
        Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        List<String> errors = new ArrayList<>();
        java.util.Set<String> invalid = new java.util.HashSet<>();
        if (patch != null) {
            for (Map.Entry<String, Object> e : patch.entrySet()) {
                Field f = byKey.get(e.getKey());
                if (f == null || !f.active()) {
                    errors.add("Unknown custom field '" + e.getKey() + "'");
                    continue;
                }
                if (e.getValue() == null || "".equals(e.getValue())) {
                    out.remove(e.getKey());
                    continue;
                }
                try {
                    Object v = coerce(f, e.getValue());
                    if ("reference".equals(f.type()) && !entities.exists(org, String.valueOf(f.options().get("entity")),
                            (String) v)) {
                        errors.add(f.label() + " refers to a record that does not exist");
                        continue;
                    }
                    out.put(f.key(), v);
                } catch (ApiException ex) {
                    errors.add(ex.getMessage());
                    invalid.add(f.key());
                }
            }
        }
        for (Field f : fields) {
            if (!f.active()) {
                continue;
            }
            if (!existing && !out.containsKey(f.key()) && f.defaultValue() != null) {
                out.put(f.key(), f.defaultValue());
            }
            if (f.required() && !out.containsKey(f.key()) && !invalid.contains(f.key())) {
                errors.add(f.label() + " is required");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", errors));
        }
        return out;
    }

    /** Coerce one value to the field's type or throw a 400 with the field label. */
    static Object coerce(Field f, Object raw) {
        String label = f.label() == null ? f.key() : f.label();
        switch (f.type()) {
            case "text", "textarea" -> {
                String s = String.valueOf(raw);
                int max = "text".equals(f.type()) ? MAX_TEXT : MAX_TEXTAREA;
                if (s.length() > max) {
                    throw ApiException.badRequest(label + " must be at most " + max + " characters");
                }
                return s;
            }
            case "number" -> {
                try {
                    BigDecimal d = new BigDecimal(String.valueOf(raw).trim());
                    return d.toBigIntegerExact().longValueExact();
                } catch (ArithmeticException | NumberFormatException e) {
                    throw ApiException.badRequest(label + " must be a whole number");
                }
            }
            case "decimal" -> {
                try {
                    return new BigDecimal(String.valueOf(raw).trim()).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest(label + " must be a number");
                }
            }
            case "date" -> {
                try {
                    return LocalDate.parse(String.valueOf(raw).trim()).toString();
                } catch (DateTimeParseException e) {
                    throw ApiException.badRequest(label + " must be a date (YYYY-MM-DD)");
                }
            }
            case "boolean" -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                String s = String.valueOf(raw).trim();
                if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                    return Boolean.parseBoolean(s);
                }
                throw ApiException.badRequest(label + " must be true or false");
            }
            case "select" -> {
                String s = String.valueOf(raw);
                if (!choices(f).contains(s)) {
                    throw ApiException.badRequest(label + " must be one of: " + String.join(", ", choices(f)));
                }
                return s;
            }
            case "multiselect" -> {
                if (!(raw instanceof Collection<?> c)) {
                    throw ApiException.badRequest(label + " must be a list");
                }
                List<String> out = new ArrayList<>();
                for (Object o : c) {
                    String s = String.valueOf(o);
                    if (!choices(f).contains(s)) {
                        throw ApiException.badRequest(label + " must only contain: " + String.join(", ", choices(f)));
                    }
                    if (!out.contains(s)) {
                        out.add(s);
                    }
                }
                return out;
            }
            case "reference" -> {
                String s = String.valueOf(raw).trim();
                if (s.isEmpty()) {
                    throw ApiException.badRequest(label + " must be a record id");
                }
                return s;
            }
            default -> throw ApiException.badRequest("Unknown field type '" + f.type() + "'");
        }
    }

    private static List<String> choices(Field f) {
        Object v = f.options().get("values");
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            c.forEach(o -> out.add(String.valueOf(o)));
        }
        return out;
    }

    private Map<String, Object> normalizeOptions(String type, Map<String, Object> options) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("select".equals(type) || "multiselect".equals(type)) {
            Object v = options == null ? null : options.get("values");
            if (!(v instanceof Collection<?> c) || c.isEmpty()) {
                throw ApiException.badRequest("options.values must list the choices of a " + type + " field");
            }
            List<String> values = new ArrayList<>();
            for (Object o : c) {
                String s = String.valueOf(o).trim();
                if (s.isEmpty() || s.length() > 100) {
                    throw ApiException.badRequest("Each choice must be 1 to 100 characters");
                }
                if (!values.contains(s)) {
                    values.add(s);
                }
            }
            out.put("values", values);
        } else if ("reference".equals(type)) {
            Object e = options == null ? null : options.get("entity");
            if (e == null) {
                throw ApiException.badRequest("options.entity must name the referenced entity");
            }
            entities.assertEntity(org(), String.valueOf(e));
            out.put("entity", String.valueOf(e));
        }
        return out;
    }

    private static String label(String label) {
        if (label == null || label.isBlank()) {
            throw ApiException.badRequest("label should not be empty");
        }
        return label.trim();
    }

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .build();

    /** A JSON column value (string, JSON tree or already plain) as plain maps, lists and scalars. */
    static Object plain(Object v) {
        Object parsed = v instanceof String s ? EntityJson.parseJson(s) : v;
        if (parsed instanceof tools.jackson.databind.JsonNode node) {
            return node.isNull() ? null : JSON.treeToValue(node, Object.class);
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object v) {
        Object parsed = plain(v);
        return parsed instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
    }
}
