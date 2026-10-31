package com.inventoryerp.core.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product grades, attribute definitions and each product's specification (INV-2). A grade and an attribute are
 * organisation masters; an attribute may be limited to a category and may be required there. A product's specification
 * is saved as one set, checked against the attribute types. Nothing here touches the {@code Product} table.
 */
@Service
public class ProductSpecService {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";
    private static final List<String> DATA_TYPES = List.of("text", "number", "boolean", "option");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProductSpecService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static int intOf(Object v, int fallback) {
        if (blank(v)) {
            return fallback;
        }
        try {
            return new BigDecimal(v.toString()).intValue();
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid number: " + v);
        }
    }

    // ─── grades ───

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listGrades(Map<String, String> q) {
        String active = "true".equals(q.get("activeOnly")) ? " AND \"isActive\"" : "";
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ProductGrade\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS"
                + " NULL" + active + " ORDER BY sequence, code").param(org()));
    }

    @Transactional
    public Map<String, Object> createGrade(Map<String, Object> dto) {
        if (blank(dto.get("code")) || blank(dto.get("name"))) {
            throw ApiException.badRequest("code and name are required");
        }
        String code = str(dto.get("code")).trim().toUpperCase();
        assertCodeFree("ProductGrade", code, "Grade");
        Map<String, Object> row = SqlRows.one(jdbc.sql("INSERT INTO \"ProductGrade\" (id, \"organizationId\", code, name,"
                + " description, sequence, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *")
                .params(UUID.randomUUID().toString(), org(), code, str(dto.get("name")), str(dto.get("description")),
                        intOf(dto.get("sequence"), 10), TenantContext.userId()));
        audit.recordInTx(AuditInput.of("ProductGrade", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateGrade(String id, Map<String, Object> dto) {
        Map<String, Object> row = require("ProductGrade", id, "Grade not found");
        Map<String, Object> out = SqlRows.one(jdbc.sql("UPDATE \"ProductGrade\" SET name = ?, description = ?, sequence = ?,"
                + " \"isActive\" = ?, \"updatedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                + " RETURNING *").params(dto.containsKey("name") ? str(dto.get("name")) : row.get("name"),
                dto.containsKey("description") ? str(dto.get("description")) : row.get("description"),
                intOf(dto.get("sequence"), (Integer) row.get("sequence")),
                dto.containsKey("isActive") ? Boolean.TRUE.equals(dto.get("isActive")) : row.get("isActive"),
                TenantContext.userId(), id, org()));
        audit.recordInTx(AuditInput.of("ProductGrade", id, "update", row, out));
        return out;
    }

    @Transactional
    public Map<String, Object> deleteGrade(String id) {
        require("ProductGrade", id, "Grade not found");
        Long used = jdbc.sql("SELECT count(*) FROM \"ProductSpecification\" WHERE \"organizationId\" = ? AND \"gradeId\" = ?")
                .params(org(), id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("This grade is used by " + used + " product(s); deactivate it instead");
        }
        softDelete("ProductGrade", id);
        return Obj.of("ok", true);
    }

    // ─── attributes ───

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAttributes(Map<String, String> q) {
        StringBuilder where = new StringBuilder("\"organizationId\" = ? AND \"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        if ("true".equals(q.get("activeOnly"))) {
            where.append(" AND \"isActive\"");
        }
        if (!blank(q.get("categoryId"))) {
            where.append(" AND (\"categoryId\" IS NULL OR \"categoryId\" = ?)");
            params.add(q.get("categoryId"));
        }
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ProductAttribute\" WHERE " + where + " ORDER BY sequence, code")
                .params(params));
    }

    @Transactional
    public Map<String, Object> createAttribute(Map<String, Object> dto) {
        if (blank(dto.get("code")) || blank(dto.get("name"))) {
            throw ApiException.badRequest("code and name are required");
        }
        String code = str(dto.get("code")).trim().toUpperCase();
        assertCodeFree("ProductAttribute", code, "Attribute");
        String type = blank(dto.get("dataType")) ? "text" : str(dto.get("dataType"));
        String[] options = options(dto.get("options"), type);
        assertCategory(dto.get("categoryId"));
        Map<String, Object> row = SqlRows.one(jdbc.sql("INSERT INTO \"ProductAttribute\" (id, \"organizationId\", code, name,"
                + " \"dataType\", unit, options, \"categoryId\", \"isRequired\", sequence, \"createdBy\") VALUES (?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?) RETURNING *")
                .params(UUID.randomUUID().toString(), org(), code, str(dto.get("name")), type, str(dto.get("unit")), options,
                        blank(dto.get("categoryId")) ? null : str(dto.get("categoryId")),
                        Boolean.TRUE.equals(dto.get("isRequired")), intOf(dto.get("sequence"), 10), TenantContext.userId()));
        audit.recordInTx(AuditInput.of("ProductAttribute", (String) row.get("id"), "create", null, row));
        return row;
    }

    /** The code and data type are fixed once created, so stored values keep their meaning. */
    @Transactional
    public Map<String, Object> updateAttribute(String id, Map<String, Object> dto) {
        Map<String, Object> row = require("ProductAttribute", id, "Attribute not found");
        String type = (String) row.get("dataType");
        String[] options = dto.containsKey("options") ? options(dto.get("options"), type)
                : strings(row.get("options")).toArray(String[]::new);
        if (dto.containsKey("categoryId")) {
            assertCategory(dto.get("categoryId"));
        }
        Object category = dto.containsKey("categoryId") ? (blank(dto.get("categoryId")) ? null : str(dto.get("categoryId")))
                : row.get("categoryId");
        Map<String, Object> out = SqlRows.one(jdbc.sql("UPDATE \"ProductAttribute\" SET name = ?, unit = ?, options = ?,"
                + " \"categoryId\" = ?, \"isRequired\" = ?, sequence = ?, \"isActive\" = ?, \"updatedBy\" = ?, \"updatedAt\" = "
                + NOW + " WHERE id = ? AND \"organizationId\" = ? RETURNING *")
                .params(dto.containsKey("name") ? str(dto.get("name")) : row.get("name"),
                        dto.containsKey("unit") ? str(dto.get("unit")) : row.get("unit"), options, category,
                        dto.containsKey("isRequired") ? Boolean.TRUE.equals(dto.get("isRequired")) : row.get("isRequired"),
                        intOf(dto.get("sequence"), (Integer) row.get("sequence")),
                        dto.containsKey("isActive") ? Boolean.TRUE.equals(dto.get("isActive")) : row.get("isActive"),
                        TenantContext.userId(), id, org()));
        audit.recordInTx(AuditInput.of("ProductAttribute", id, "update", row, out));
        return out;
    }

    @Transactional
    public Map<String, Object> deleteAttribute(String id) {
        require("ProductAttribute", id, "Attribute not found");
        Long used = jdbc.sql("SELECT count(*) FROM \"ProductAttributeValue\" WHERE \"organizationId\" = ? AND"
                + " \"attributeId\" = ?").params(org(), id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("This attribute is used by " + used + " product(s); deactivate it instead");
        }
        softDelete("ProductAttribute", id);
        return Obj.of("ok", true);
    }

    // ─── product specifications ───

    /** Products with a specification, filtered by {@code gradeId}, {@code attributeId} and {@code value}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSpecifications(Map<String, String> q) {
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!blank(q.get("gradeId"))) {
            where.append(" AND s.\"gradeId\" = ?");
            params.add(q.get("gradeId"));
        }
        if (!blank(q.get("attributeId"))) {
            where.append(" AND EXISTS (SELECT 1 FROM \"ProductAttributeValue\" v WHERE v.\"productId\" = s.\"productId\" AND"
                    + " v.\"attributeId\" = ?");
            params.add(q.get("attributeId"));
            if (!blank(q.get("value"))) {
                where.append(" AND (v.\"valueText\" = ? OR v.\"valueNumber\"::text = ? OR v.\"valueBoolean\"::text = ?)");
                params.addAll(List.of(q.get("value"), q.get("value"), q.get("value")));
            }
            where.append(')');
        }
        return SqlRows.list(jdbc.sql("SELECT s.*, p.code AS \"productCode\", p.name AS \"productName\", g.code AS"
                + " \"gradeCode\", g.name AS \"gradeName\" FROM \"ProductSpecification\" s JOIN \"Product\" p ON p.id ="
                + " s.\"productId\" LEFT JOIN \"ProductGrade\" g ON g.id = s.\"gradeId\"" + where
                + " ORDER BY p.code LIMIT 500").params(params));
    }

    /** A product's grade, standard and attribute values, plus the attributes that apply to its category. */
    @Transactional(readOnly = true)
    public Map<String, Object> getSpecification(String productId) {
        Map<String, Object> product = requireProduct(productId);
        Map<String, Object> spec = SqlRows.one(jdbc.sql("SELECT s.*, g.code AS \"gradeCode\", g.name AS \"gradeName\" FROM"
                + " \"ProductSpecification\" s LEFT JOIN \"ProductGrade\" g ON g.id = s.\"gradeId\" WHERE s.\"organizationId\""
                + " = ? AND s.\"productId\" = ?").params(org(), productId));
        List<Map<String, Object>> values = SqlRows.list(jdbc.sql("SELECT v.\"attributeId\", v.\"valueText\","
                + " v.\"valueNumber\", v.\"valueBoolean\", a.code, a.name, a.\"dataType\", a.unit FROM"
                + " \"ProductAttributeValue\" v JOIN \"ProductAttribute\" a ON a.id = v.\"attributeId\" WHERE"
                + " v.\"organizationId\" = ? AND v.\"productId\" = ? ORDER BY a.sequence, a.code").params(org(), productId));
        Map<String, String> filter = new LinkedHashMap<>();
        filter.put("activeOnly", "true");
        if (product.get("categoryId") != null) {
            filter.put("categoryId", str(product.get("categoryId")));
        }
        return Obj.of("productId", productId, "productCode", product.get("code"), "productName", product.get("name"),
                "specification", spec, "values", values, "attributes", applicable(filter, product));
    }

    private List<Map<String, Object>> applicable(Map<String, String> filter, Map<String, Object> product) {
        if (product.get("categoryId") != null) {
            return listAttributes(filter);
        }
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ProductAttribute\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS"
                + " NULL AND \"isActive\" AND \"categoryId\" IS NULL ORDER BY sequence, code").param(org()));
    }

    /** Save a product's specification and attribute values as one set; attributes left out are cleared. */
    @Transactional
    public Map<String, Object> saveSpecification(String productId, Map<String, Object> dto) {
        Map<String, Object> product = requireProduct(productId);
        String gradeId = blank(dto.get("gradeId")) ? null : str(dto.get("gradeId"));
        if (gradeId != null) {
            Map<String, Object> grade = require("ProductGrade", gradeId, "Grade not found");
            if (!Boolean.TRUE.equals(grade.get("isActive"))) {
                throw ApiException.badRequest("Grade " + grade.get("code") + " is not active");
            }
        }
        List<Value> values = values(dto.get("attributes"), product);
        jdbc.sql("INSERT INTO \"ProductSpecification\" (id, \"organizationId\", \"productId\", \"gradeId\", \"standardRef\","
                + " specification, \"updatedBy\") VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"productId\")"
                + " DO UPDATE SET \"gradeId\" = EXCLUDED.\"gradeId\", \"standardRef\" = EXCLUDED.\"standardRef\","
                + " specification = EXCLUDED.specification, \"updatedBy\" = EXCLUDED.\"updatedBy\", \"updatedAt\" = " + NOW)
                .params(UUID.randomUUID().toString(), org(), productId, gradeId, str(dto.get("standardRef")),
                        str(dto.get("specification")), TenantContext.userId()).update();
        jdbc.sql("DELETE FROM \"ProductAttributeValue\" WHERE \"organizationId\" = ? AND \"productId\" = ?")
                .params(org(), productId).update();
        for (Value v : values) {
            jdbc.sql("INSERT INTO \"ProductAttributeValue\" (id, \"organizationId\", \"productId\", \"attributeId\","
                    + " \"valueText\", \"valueNumber\", \"valueBoolean\") VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), productId, v.attributeId(), v.text(), v.number(),
                            v.bool()).update();
        }
        audit.recordInTx(AuditInput.of("ProductSpecification", productId, "update", null, Obj.of("gradeId", gradeId,
                "standardRef", dto.get("standardRef"), "attributes", values.size())));
        return getSpecification(productId);
    }

    /** One checked attribute value: exactly one of the three is set. */
    private record Value(String attributeId, String text, BigDecimal number, Boolean bool) {}

    private List<Value> values(Object raw, Map<String, Object> product) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT * FROM \"ProductAttribute\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").param(org()))) {
            byId.put((String) a.get("id"), a);
        }
        List<Value> out = new ArrayList<>();
        Set<String> given = new HashSet<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw ApiException.badRequest("attributes must be a list of { attributeId, value }");
                }
                String attributeId = str(m.get("attributeId"));
                Object value = m.get("value");
                Map<String, Object> attribute = byId.get(attributeId);
                if (attribute == null) {
                    throw ApiException.notFound("Attribute not found");
                }
                Object category = attribute.get("categoryId");
                if (category != null && !category.equals(product.get("categoryId"))) {
                    throw ApiException.badRequest("Attribute " + attribute.get("code")
                            + " does not apply to this product's category");
                }
                if (blank(value)) {
                    continue;
                }
                if (!given.add(attributeId)) {
                    throw ApiException.badRequest("Attribute " + attribute.get("code") + " appears twice");
                }
                out.add(value(attribute, value));
            }
        }
        for (Map<String, Object> a : byId.values()) {
            boolean applies = a.get("categoryId") == null || a.get("categoryId").equals(product.get("categoryId"));
            if (applies && Boolean.TRUE.equals(a.get("isRequired")) && Boolean.TRUE.equals(a.get("isActive"))
                    && !given.contains(a.get("id"))) {
                throw ApiException.badRequest("Attribute " + a.get("name") + " is required");
            }
        }
        return out;
    }

    private static Value value(Map<String, Object> attribute, Object value) {
        String id = (String) attribute.get("id");
        String text = value.toString().trim();
        switch ((String) attribute.get("dataType")) {
            case "number":
                try {
                    return new Value(id, null, new BigDecimal(text), null);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("Attribute " + attribute.get("code") + " must be a number");
                }
            case "boolean":
                if (!"true".equals(text) && !"false".equals(text)) {
                    throw ApiException.badRequest("Attribute " + attribute.get("code") + " must be true or false");
                }
                return new Value(id, null, null, Boolean.valueOf(text));
            case "option":
                if (!strings(attribute.get("options")).contains(text)) {
                    throw ApiException.badRequest("Attribute " + attribute.get("code") + " must be one of its options");
                }
                return new Value(id, text, null, null);
            default:
                return new Value(id, text, null, null);
        }
    }

    // ─── helpers ───

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            list.forEach(o -> out.add(str(o)));
        }
        return out;
    }

    private static String[] options(Object raw, String type) {
        if (!DATA_TYPES.contains(type)) {
            throw ApiException.badRequest("Invalid dataType: " + type);
        }
        List<String> options = new ArrayList<>();
        for (String s : strings(raw)) {
            if (!blank(s)) {
                options.add(s.trim());
            }
        }
        if ("option".equals(type) && options.isEmpty()) {
            throw ApiException.badRequest("An option attribute needs at least one option");
        }
        return options.toArray(String[]::new);
    }

    private void assertCodeFree(String table, String code, String what) {
        Long n = jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ? AND code = ? AND"
                + " \"deletedAt\" IS NULL").params(org(), code).query(Long.class).single();
        if (n > 0) {
            throw ApiException.badRequest(what + " code \"" + code + "\" already exists");
        }
    }

    private void assertCategory(Object categoryId) {
        if (blank(categoryId)) {
            return;
        }
        Long n = jdbc.sql("SELECT count(*) FROM \"ProductCategory\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(str(categoryId), org()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.notFound("Category not found");
        }
    }

    private Map<String, Object> require(String table, String id, String message) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"" + table + "\" WHERE id = ? AND \"organizationId\""
                + " = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (row == null) {
            throw ApiException.notFound(message);
        }
        return row;
    }

    private Map<String, Object> requireProduct(String id) {
        return require("Product", id, "Product not found");
    }

    private void softDelete(String table, String id) {
        jdbc.sql("UPDATE \"" + table + "\" SET \"deletedAt\" = " + NOW + ", \"isActive\" = false, \"updatedBy\" = ? WHERE id = ?"
                + " AND \"organizationId\" = ?").params(TenantContext.userId(), id, org()).update();
    }
}
