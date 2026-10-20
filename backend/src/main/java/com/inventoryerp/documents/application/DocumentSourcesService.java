package com.inventoryerp.documents.application;

import com.inventoryerp.documents.domain.DocumentSource;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DMS sources and attachments (port of {@code document-sources.service.ts} + {@code document-attachments.service.ts}).
 * Sources link a document to the domain record that produced it (validated only when the owning module registered
 * a validator); attachments link a File-vault file with a whitelisted role.
 */
@Service
public class DocumentSourcesService {

    public static final List<String> ATTACHMENT_ROLES = List.of("ATTACHMENT", "SIGNED_COPY");

    private final Map<String, BiPredicate<String, String>> validators = new ConcurrentHashMap<>();
    private final JdbcClient jdbc;
    private final DmsStore store;
    private final FilesService files;

    public DocumentSourcesService(JdbcClient jdbc, DmsStore store, FilesService files) {
        this.jdbc = jdbc;
        this.store = store;
        this.files = files;
    }

    /** Register a {@code (orgId, sourceId) → exists} validator from the owning domain module. */
    public void registerSourceValidator(String sourceType, BiPredicate<String, String> validator) {
        validators.put(sourceType, validator);
    }

    /** {@code {id, sourceType, sourceId}}. */
    @Transactional
    public Map<String, Object> addSource(String orgId, String userId, String documentId, String sourceType,
            String sourceId, String relationship) {
        if (sourceType == null || sourceType.trim().isEmpty() || sourceId == null || sourceId.trim().isEmpty()) {
            throw ApiException.badRequest("sourceType and sourceId are required");
        }
        store.requireDoc(documentId, orgId);
        BiPredicate<String, String> validator = validators.get(sourceType.trim());
        if (validator != null && !validator.test(orgId, sourceId.trim())) {
            throw ApiException.badRequest("Source '" + sourceType + ":" + sourceId
                    + "' does not exist in its owning module");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"DocumentSource\" (id, \"organizationId\", \"documentId\", \"sourceType\", \"sourceId\","
                        + " relationship, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, orgId, documentId, sourceType.trim(), sourceId.trim(),
                        relationship != null ? relationship : "primary", userId)
                .update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("sourceType", sourceType.trim());
        out.put("sourceId", sourceId.trim());
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSources(String orgId, String documentId) {
        store.requireDoc(documentId, orgId);
        return SqlRows.list(DocumentSource.class, jdbc.sql("SELECT * FROM \"DocumentSource\" WHERE \"organizationId\" = ?"
                + " AND \"documentId\" = ? ORDER BY \"createdAt\" ASC").params(orgId, documentId));
    }

    @Transactional
    public Map<String, Object> removeSource(String orgId, String documentId, String sourceId) {
        int n = jdbc.sql("DELETE FROM \"DocumentSource\" WHERE id = ? AND \"organizationId\" = ? AND \"documentId\" = ?")
                .params(sourceId, orgId, documentId).update();
        if (n == 0) {
            throw ApiException.notFound("Source link not found");
        }
        return Obj.of("deleted", true);
    }

    // ------------------------------------------------------------------ attachments

    /** Store the upload in the File vault (owner {@code document}) and link it with a role. */
    @Transactional
    public Map<String, Object> attach(String orgId, String userId, String documentId, String role, String kind,
            Object metadata, String filename, String contentType, byte[] bytes) {
        String r = (role != null ? role : "ATTACHMENT").toUpperCase();
        if (!ATTACHMENT_ROLES.contains(r)) {
            throw ApiException.badRequest("Attachment role must be one of " + String.join(", ", ATTACHMENT_ROLES)
                    + " (GENERATED_OUTPUT and SOURCE_FILE are engine-managed)");
        }
        store.requireDoc(documentId, orgId);
        Map<String, Object> stored = files.upload(filename, contentType, bytes, "document", documentId);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"DocumentAttachment\" (id, \"organizationId\", \"documentId\", \"fileId\", role, kind,"
                        + " version, metadata, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, 1, ?::jsonb, ?)")
                .params(id, orgId, documentId, stored.get("id"), r, kind, store.toJson(metadata == null ? Map.of()
                        : metadata), userId)
                .update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("role", r);
        out.put("fileId", stored.get("id"));
        out.put("fileName", filename);
        out.put("size", bytes.length);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAttachments(String orgId, String documentId) {
        store.requireDoc(documentId, orgId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                        SELECT a.id, a.role, a.kind, a.version, a."createdAt", a."createdById", f.id AS "fileId", f.filename,
                               f."contentType", f."byteSize", f."storageKey"
                        FROM "DocumentAttachment" a JOIN "File" f ON f.id = a."fileId"
                        WHERE a."organizationId" = ? AND a."documentId" = ? ORDER BY a."createdAt" ASC""")
                .params(orgId, documentId).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("role", r.get("role"));
            m.put("kind", r.get("kind"));
            m.put("version", r.get("version"));
            m.put("createdAt", SqlRows.value(r.get("createdAt")));
            m.put("createdById", r.get("createdById"));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", r.get("fileId"));
            f.put("fileName", r.get("filename"));
            f.put("contentType", r.get("contentType"));
            f.put("size", r.get("byteSize"));
            f.put("storageKey", r.get("storageKey"));
            f.put("downloadUrl", files.signDownload((String) r.get("fileId")));
            m.put("file", f);
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> removeAttachment(String orgId, String documentId, String attachmentId) {
        int n = jdbc.sql("DELETE FROM \"DocumentAttachment\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"documentId\" = ?").params(attachmentId, orgId, documentId).update();
        if (n == 0) {
            throw ApiException.notFound("Attachment not found");
        }
        return Obj.of("deleted", true);
    }
}
