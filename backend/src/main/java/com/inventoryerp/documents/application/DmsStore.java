package com.inventoryerp.documents.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared DMS row access: raw document / type rows and jsonb ↔ plain Java conversion. */
@Component
public class DmsStore {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    final JdbcClient jdbc;
    final JsonMapper mapper;

    public DmsStore(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Raw {@code Document} row of the organization (JDBC types), or null. */
    Map<String, Object> rawDoc(String id, String orgId) {
        return jdbc.sql("SELECT * FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(id, orgId).query()
                .listOfRows().stream().findFirst().orElse(null);
    }

    /** Raw document or 404 {@code Document not found}. */
    Map<String, Object> requireDoc(String id, String orgId) {
        Map<String, Object> doc = rawDoc(id, orgId);
        if (doc == null) {
            throw ApiException.notFound("Document not found");
        }
        return doc;
    }

    /** Raw {@code DocumentTypeDef} row by id, or null. */
    Map<String, Object> rawDef(Object id) {
        if (id == null) {
            return null;
        }
        return jdbc.sql("SELECT * FROM \"DocumentTypeDef\" WHERE id = ?").param(id).query().listOfRows().stream()
                .findFirst().orElse(null);
    }

    /** Raw {@code DocumentTypeDef} row by code, or null. */
    Map<String, Object> rawDefByCode(String code) {
        return jdbc.sql("SELECT * FROM \"DocumentTypeDef\" WHERE code = ?").param(code).query().listOfRows().stream()
                .findFirst().orElse(null);
    }

    /** Ordered raw lines of a document. */
    List<Map<String, Object>> rawLines(Object documentId) {
        return jdbc.sql("SELECT * FROM \"DocumentLine\" WHERE \"documentId\" = ? ORDER BY \"lineNumber\" ASC")
                .param(documentId).query().listOfRows();
    }

    /** jsonb / JsonNode / string → plain Java (Map, List, String, Number, Boolean) or null. */
    Object json(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof PGobject pg) {
            return pg.getValue() == null ? null : mapper.readValue(pg.getValue(), Object.class);
        }
        if (v instanceof JsonNode node) {
            return node.isNull() ? null : mapper.treeToValue(node, Object.class);
        }
        return v;
    }

    /** jsonb column as a map (empty when null / not an object). */
    @SuppressWarnings("unchecked")
    Map<String, Object> jsonMap(Object v) {
        Object o = json(v);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Serialize for a {@code ?::jsonb} parameter. */
    String toJson(Object v) {
        return v == null ? null : mapper.writeValueAsString(v);
    }

    /** Permission check against a type row ({@code code}, {@code category}, {@code security}). */
    boolean can(List<String> keys, Map<String, Object> def, String action) {
        if (def == null) {
            return DmsRules.can(keys, null, null, null, action);
        }
        return DmsRules.can(keys, (String) def.get("code"), (String) def.get("category"), json(def.get("security")),
                action);
    }

    String resolveKey(List<String> keys, Map<String, Object> def, String action) {
        return DmsRules.resolveKey(keys, (String) def.get("code"), (String) def.get("category"),
                json(def.get("security")), action);
    }

    /** A raw row rendered like Prisma ({@link SqlRows#as}). */
    static Map<String, Object> shape(Class<?> entity, Map<String, Object> raw) {
        return raw == null ? null : SqlRows.as(entity, raw);
    }
}
