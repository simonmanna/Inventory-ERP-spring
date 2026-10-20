package com.inventoryerp.documents.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * DMS relations (port of {@code document-relations.service.ts}): creation validated against the relation-type
 * registry (categories, cardinality, inverse-implicit mirror), both-direction listing, cycle-safe BFS chain and
 * delete (with the implicit mirror).
 */
@Service
public class DocumentRelationsService {

    private final JdbcClient jdbc;
    private final DmsStore store;

    public DocumentRelationsService(JdbcClient jdbc, DmsStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    private Map<String, Object> relType(String code) {
        return jdbc.sql("SELECT * FROM \"DocumentRelationType\" WHERE code = ?").param(code).query().listOfRows().stream()
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object v) {
        Object n = SqlRows.value(v);
        return n instanceof List<?> l ? (List<String>) l : List.of();
    }

    /** {@code {id, mirrorId}}. */
    public Map<String, Object> createRelation(String orgId, String userId, String fromId, String toId, String code,
            Map<String, Object> payload) {
        Map<String, Object> from = store.rawDoc(fromId, orgId);
        Map<String, Object> to = store.rawDoc(toId, orgId);
        Map<String, Object> type = relType(code);
        if (from == null) {
            throw ApiException.notFound("Source document not found");
        }
        if (to == null) {
            throw ApiException.notFound("Target document not found");
        }
        if (type == null) {
            throw ApiException.badRequest("Relation type '" + code + "' is not registered");
        }
        long currentSourceCount = jdbc.sql("SELECT count(*) FROM \"DocumentRelation\" WHERE \"fromDocumentId\" = ? AND"
                + " \"organizationId\" = ? AND \"relationTypeId\" = ?").params(from.get("id"), orgId, type.get("id"))
                .query(Long.class).single();
        Map<String, Object> fromDef = store.rawDef(from.get("documentTypeId"));
        Map<String, Object> toDef = store.rawDef(to.get("documentTypeId"));
        String reason = DmsRules.validateRelationCreation(code, list(type.get("allowedSourceCategories")),
                list(type.get("allowedTargetCategories")), (String) type.get("cardinality"),
                fromDef == null ? "custom" : (String) fromDef.get("category"),
                toDef == null ? "custom" : (String) toDef.get("category"), currentSourceCount);
        if (reason != null) {
            throw ApiException.badRequest(reason);
        }
        String id = UUID.randomUUID().toString();
        try {
            insert(id, orgId, from.get("id"), to.get("id"), type.get("id"), payload == null ? Map.of() : payload, userId);
        } catch (DuplicateKeyException err) {
            throw ApiException.conflict("Relation '" + code + "' already exists between these documents");
        }
        String mirrorId = null;
        if (Boolean.TRUE.equals(type.get("inverseImplicit"))) {
            boolean exists = !jdbc.sql("SELECT 1 FROM \"DocumentRelation\" WHERE \"fromDocumentId\" = ? AND"
                    + " \"toDocumentId\" = ? AND \"relationTypeId\" = ? AND \"organizationId\" = ?")
                    .params(to.get("id"), from.get("id"), type.get("id"), orgId).query().listOfRows().isEmpty();
            if (!exists) {
                mirrorId = UUID.randomUUID().toString();
                insert(mirrorId, orgId, to.get("id"), from.get("id"), type.get("id"), Map.of("mirrored", true), userId);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("mirrorId", mirrorId);
        return out;
    }

    private void insert(String id, String orgId, Object from, Object to, Object typeId, Map<String, Object> payload,
            String userId) {
        jdbc.sql("INSERT INTO \"DocumentRelation\" (id, \"organizationId\", \"fromDocumentId\", \"toDocumentId\","
                        + " \"relationTypeId\", payload, \"createdById\") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)")
                .params(id, orgId, from, to, typeId, store.toJson(payload), userId).update();
    }

    /** Relations in both directions, oldest first. */
    public List<Map<String, Object>> listRelations(String orgId, String documentId) {
        store.requireDoc(documentId, orgId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                        SELECT r.id, t.code, r."fromDocumentId", f."documentNumber" AS "fromNumber", r."toDocumentId",
                               d."documentNumber" AS "toNumber", r.payload, r."createdAt"
                        FROM "DocumentRelation" r
                        JOIN "DocumentRelationType" t ON t.id = r."relationTypeId"
                        JOIN "Document" f ON f.id = r."fromDocumentId"
                        JOIN "Document" d ON d.id = r."toDocumentId"
                        WHERE r."organizationId" = ? AND (r."fromDocumentId" = ? OR r."toDocumentId" = ?)
                        ORDER BY r."createdAt" ASC""").params(orgId, documentId, documentId).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("relationType", r.get("code"));
            m.put("direction", documentId.equals(r.get("fromDocumentId")) ? "out" : "in");
            m.put("fromDocumentId", r.get("fromDocumentId"));
            m.put("fromNumber", r.get("fromNumber"));
            m.put("toDocumentId", r.get("toDocumentId"));
            m.put("toNumber", r.get("toNumber"));
            m.put("payload", SqlRows.value(r.get("payload")));
            m.put("createdAt", SqlRows.value(r.get("createdAt")));
            out.add(m);
        }
        return out;
    }

    /** BFS chain with visited set + depth cap 10. */
    public Map<String, Object> relationChain(String orgId, String documentId, String code) {
        Map<String, Object> type = relType(code);
        if (type == null) {
            throw ApiException.badRequest("Relation type '" + code + "' is not registered");
        }
        List<Map<String, Object>> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>(Set.of(documentId));
        record Node(String id, int depth) {}
        Deque<Node> queue = new ArrayDeque<>(List.of(new Node(documentId, 0)));
        while (!queue.isEmpty()) {
            Node current = queue.poll();
            if (current.depth() > 0) {
                chain.add(Obj.of("documentId", current.id(), "depth", current.depth(), "via", code));
            }
            if (current.depth() >= 10) {
                continue;
            }
            for (Map<String, Object> n : jdbc.sql("SELECT \"fromDocumentId\", \"toDocumentId\" FROM \"DocumentRelation\""
                    + " WHERE \"organizationId\" = ? AND \"relationTypeId\" = ? AND (\"fromDocumentId\" = ? OR"
                    + " \"toDocumentId\" = ?)").params(orgId, type.get("id"), current.id(), current.id()).query()
                    .listOfRows()) {
                String next = current.id().equals(n.get("fromDocumentId")) ? (String) n.get("toDocumentId")
                        : (String) n.get("fromDocumentId");
                if (visited.add(next)) {
                    queue.add(new Node(next, current.depth() + 1));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("relationCode", code);
        out.put("chain", chain);
        return out;
    }

    /** Delete a relation touching the document (+ its flagged implicit mirror). */
    public Map<String, Object> deleteRelation(String orgId, String documentId, String relationId) {
        Map<String, Object> row = jdbc.sql("SELECT * FROM \"DocumentRelation\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND (\"fromDocumentId\" = ? OR \"toDocumentId\" = ?)")
                .params(relationId, orgId, documentId, documentId).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null) {
            throw ApiException.notFound("Relation not found");
        }
        jdbc.sql("DELETE FROM \"DocumentRelation\" WHERE id = ? AND \"organizationId\" = ?").params(row.get("id"), orgId)
                .update();
        jdbc.sql("DELETE FROM \"DocumentRelation\" WHERE \"organizationId\" = ? AND \"fromDocumentId\" = ? AND"
                        + " \"toDocumentId\" = ? AND \"relationTypeId\" = ? AND payload -> 'mirrored' = 'true'::jsonb")
                .params(orgId, row.get("toDocumentId"), row.get("fromDocumentId"), row.get("relationTypeId")).update();
        return Obj.of("deleted", true);
    }
}
