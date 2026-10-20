package com.inventoryerp.documents.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * DMS print orchestration (port of {@code document-print.service.ts}): print permission, idempotent print log,
 * REPRINT reason, opt-in {@code snapshotOnPrint}, render.
 */
@Service
public class DocumentPrintService {

    /** {@code PrintInput}. */
    public record PrintInput(String orgId, String userId, List<String> userPermissions, String documentId,
            String format, Integer copies, String printer, String reason, String idempotencyKey) {}

    private final JdbcClient jdbc;
    private final DmsStore store;
    private final DocumentRenderService render;

    public DocumentPrintService(JdbcClient jdbc, DmsStore store, DocumentRenderService render) {
        this.jdbc = jdbc;
        this.store = store;
        this.render = render;
    }

    public Map<String, Object> print(PrintInput input) {
        if (input.idempotencyKey() == null || input.idempotencyKey().trim().isEmpty()) {
            throw ApiException.badRequest("idempotencyKey is required");
        }
        String key = input.idempotencyKey().trim();
        render.assertFormat(input.format());
        Map<String, Object> doc = store.requireDoc(input.documentId(), input.orgId());
        Map<String, Object> def = store.rawDef(doc.get("documentTypeId"));
        if (store.resolveKey(input.userPermissions(), def, "print") == null) {
            throw ApiException.forbidden("Missing permission to print '" + def.get("code") + "' documents");
        }
        Object docId = doc.get("id");
        Map<String, Object> prior = jdbc.sql("SELECT * FROM \"DocumentPrintLog\" WHERE \"idempotencyKey\" = ? AND"
                + " \"organizationId\" = ? AND \"documentId\" = ? LIMIT 1").params(key, input.orgId(), docId).query()
                .listOfRows().stream().findFirst().orElse(null);
        if (prior != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("printLogId", prior.get("id"));
            out.put("action", prior.get("action"));
            out.put("replayed", true);
            out.put("copies", prior.get("copies"));
            out.put("format", input.format());
            out.put("contentType", "text/plain");
            out.put("content", "Replayed print " + prior.get("id") + " (" + prior.get("type") + ", " + prior.get("action")
                    + ") — no re-render");
            out.put("templateKey", "");
            out.put("templateVersionId", null);
            out.put("renderedFrom", "snapshot");
            out.put("snapshotId", null);
            return out;
        }
        long priorLogs = jdbc.sql("SELECT count(*) FROM \"DocumentPrintLog\" WHERE \"documentId\" = ? AND"
                + " \"organizationId\" = ?").params(docId, input.orgId()).query(Long.class).single();
        String action = priorLogs > 0 ? "REPRINT" : "PRINT";
        if ("REPRINT".equals(action) && (input.reason() == null || input.reason().trim().isEmpty())) {
            throw ApiException.badRequest("A reprint requires a reason");
        }
        int copies = Math.max(1, Math.min(input.copies() == null ? 1 : input.copies(), 5));
        List<Map<String, Object>> lines = store.rawLines(docId);
        if (Boolean.TRUE.equals(store.jsonMap(def.get("snapshotPolicy")).get("snapshotOnPrint"))) {
            long existing = jdbc.sql("SELECT count(*) FROM \"DocumentSnapshot\" WHERE \"documentId\" = ? AND"
                    + " \"organizationId\" = ? AND reason = 'print'").params(docId, input.orgId()).query(Long.class)
                    .single();
            if (existing == 0) {
                Map<String, Object> data = DmsSnapshot.buildSnapshotData(doc, lines, (String) def.get("code"),
                        (String) def.get("name"));
                jdbc.sql("INSERT INTO \"DocumentSnapshot\" (id, \"organizationId\", \"documentId\", state, data, reason,"
                                + " \"createdById\") VALUES (?, ?, ?, ?, ?::jsonb, 'print', ?)")
                        .params(UUID.randomUUID().toString(), input.orgId(), docId, doc.get("status").toString(),
                                store.toJson(data), input.userId())
                        .update();
            }
        }
        Map<String, Object> outcome = render.render(input.orgId(), doc, lines, (String) def.get("code"),
                (String) def.get("name"), def.get("id"), input.format());
        String logId = UUID.randomUUID().toString();
        Map<String, Object> log;
        try {
            jdbc.sql("INSERT INTO \"DocumentPrintLog\" (id, \"organizationId\", \"documentId\", type, action, copies,"
                            + " \"printedById\", reason, printer, \"idempotencyKey\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(logId, input.orgId(), docId, def.get("code").toString().replace('_', '-').toUpperCase(),
                            action, copies, input.userId(), input.reason(), input.printer(), key)
                    .update();
            log = Map.of("id", logId, "action", action, "idempotencyKey", key);
        } catch (DataIntegrityViolationException err) {
            // Global unique index on idempotencyKey — another document used this key first: replay that row.
            Map<String, Object> existing = jdbc.sql("SELECT id, action, \"idempotencyKey\" FROM \"DocumentPrintLog\""
                    + " WHERE \"idempotencyKey\" = ?").param(key).query().listOfRows().stream().findFirst().orElse(null);
            if (existing == null) {
                throw err;
            }
            log = existing;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("printLogId", log.get("id"));
        out.put("action", log.get("action"));
        out.put("replayed", !(key.equals(log.get("idempotencyKey")) && "PRINT".equals(action)));
        out.put("copies", copies);
        out.putAll(outcome);
        return out;
    }
}
