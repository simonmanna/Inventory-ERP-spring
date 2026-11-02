package com.inventoryerp.dms.application;

import com.inventoryerp.dms.application.DmsWorkspaceService.Level;
import com.inventoryerp.dms.spi.DmsAttachments;
import com.inventoryerp.dms.spi.DmsResourceProvider;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Documents: upload (one or many files), versions, rename / move / tag, links to ERP records, lock, archive, soft
 * delete and restore, approval, signed download URLs, activity timeline and record attachments. A document is readable
 * with access to its workspace or to a record it is linked to; it is writable with write access to its workspace or
 * write permission on a linked record. Linked ({@code sourceKind = linked}) documents mirror attachments owned by
 * another module and are read-only here.
 */
@Service
public class DmsDocumentService implements DmsAttachments {

    public static final String OWNER_TYPE = "dms_document";
    public static final String APPROVAL_ENTITY = "dms_document";
    static final Set<String> STATUSES = Set.of("active", "pending_approval", "approved", "rejected", "archived");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final FilesService files;
    private final DmsWorkspaceService workspaces;
    private final DmsResourceRegistry resources;
    private final EventOutboxService outbox;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final DmsTextExtractor extractor;

    public DmsDocumentService(JdbcClient jdbc, JsonMapper json, FilesService files, DmsWorkspaceService workspaces,
            DmsResourceRegistry resources, EventOutboxService outbox, AuditService audit, ApprovalsService approvals,
            DmsTextExtractor extractor) {
        this.jdbc = jdbc;
        this.json = json;
        this.files = files;
        this.workspaces = workspaces;
        this.resources = resources;
        this.outbox = outbox;
        this.audit = audit;
        this.approvals = approvals;
        this.extractor = extractor;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** One uploaded file. */
    public record FilePart(String filename, String contentType, byte[] bytes) {}

    /** Upload metadata (all optional). */
    public record UploadInput(String workspaceId, String folderId, String resType, String resId, List<String> tags,
            String description, LocalDate expiresOn, Integer reminderLeadDays, String partnerId) {}

    /** List filters. */
    public record ListFilter(String workspaceId, String folderId, boolean rootOnly, String tagId, String resType,
            String resId, String q, String status, LocalDate expiringBefore, boolean deleted, boolean mine,
            String sort, int page, int pageSize) {}

    // ---- reading ----

    Map<String, Object> raw(String id) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT * FROM \"DmsDocument\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (d == null) {
            throw ApiException.notFound("Document not found");
        }
        d.remove("searchVector");
        return d;
    }

    private List<Map<String, Object>> linksOf(String documentId) {
        return SqlRows.list(jdbc.sql("SELECT id, \"resType\", \"resId\", \"createdAt\" FROM \"DmsDocumentLink\" WHERE"
                + " \"documentId\" = ? ORDER BY \"createdAt\"").param(documentId));
    }

    private boolean readableByLink(String documentId) {
        for (Map<String, Object> l : linksOf(documentId)) {
            DmsResourceProvider p = resources.find((String) l.get("resType"));
            if (p != null && DmsResourceRegistry.canRead(p)) {
                return true;
            }
        }
        return false;
    }

    private boolean writableByLink(String documentId) {
        for (Map<String, Object> l : linksOf(documentId)) {
            DmsResourceProvider p = resources.find((String) l.get("resType"));
            if (p != null && DmsResourceRegistry.canRead(p) && DmsResourceRegistry.canWrite(p)) {
                return true;
            }
        }
        return false;
    }

    /** The document if the caller may read it (404 for both missing and hidden, so ids are not probed). */
    Map<String, Object> readable(String id) {
        Map<String, Object> d = raw(id);
        if (workspaces.level((String) d.get("workspaceId")).ordinal() < Level.READ.ordinal() && !readableByLink(id)) {
            throw ApiException.notFound("Document not found");
        }
        return d;
    }

    /** The document if the caller may change it. */
    Map<String, Object> writable(String id) {
        Map<String, Object> d = readable(id);
        if (d.get("deletedAt") != null) {
            throw ApiException.badRequest("Document is deleted");
        }
        if ("linked".equals(d.get("sourceKind"))) {
            throw ApiException.badRequest("This file is managed by its module and is read-only here");
        }
        if (workspaces.level((String) d.get("workspaceId")).ordinal() < Level.WRITE.ordinal() && !writableByLink(id)) {
            throw ApiException.forbidden("You do not have write access to this document");
        }
        Object locked = d.get("lockedById");
        if (locked != null && !locked.equals(TenantContext.userId())
                && !TenantContext.has(Permissions.DMS__WORKSPACE_MANAGE)) {
            throw ApiException.conflict("Document is locked by another user");
        }
        return d;
    }

    /** Document JSON: row + tags, links (with record labels and web paths), owner / locker names, access. */
    List<Map<String, Object>> shape(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<String> ids = rows.stream().map(r -> (String) r.get("id")).toList();
        Map<String, List<Map<String, Object>>> tags = new HashMap<>();
        for (Map<String, Object> t : SqlRows.list(jdbc.sql("""
                SELECT dt."documentId", t.id, t.name, t.color, t."categoryId" FROM "DmsDocumentTag" dt
                JOIN "DmsTag" t ON t.id = dt."tagId" WHERE dt."documentId" IN (:ids) ORDER BY t.name""")
                .param("ids", ids))) {
            String doc = (String) t.remove("documentId");
            tags.computeIfAbsent(doc, k -> new ArrayList<>()).add(t);
        }
        Map<String, List<Map<String, Object>>> links = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"documentId\", \"resType\", \"resId\" FROM"
                + " \"DmsDocumentLink\" WHERE \"documentId\" IN (:ids) ORDER BY \"createdAt\"").param("ids", ids))) {
            String doc = (String) l.remove("documentId");
            DmsResourceProvider p = resources.find((String) l.get("resType"));
            if (p != null) {
                l.put("typeLabel", p.label());
                boolean visible = DmsResourceRegistry.canRead(p);
                l.put("label", visible ? p.displayName((String) l.get("resId")) : null);
                l.put("webPath", visible ? p.webPath((String) l.get("resId")) : null);
            }
            links.computeIfAbsent(doc, k -> new ArrayList<>()).add(l);
        }
        Map<String, String> names = new HashMap<>();
        Set<String> users = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            for (String k : List.of("ownerUserId", "lockedById", "createdById")) {
                if (r.get(k) != null) {
                    users.add((String) r.get(k));
                }
            }
        }
        if (!users.isEmpty()) {
            for (Map<String, Object> u : SqlRows.list(jdbc.sql("SELECT id, trim(coalesce(\"firstName\", '') || ' ' ||"
                    + " coalesce(\"lastName\", '')) AS n, email FROM \"User\" WHERE id IN (:ids)")
                    .param("ids", List.copyOf(users)))) {
                String n = (String) u.get("n");
                names.put((String) u.get("id"), n == null || n.isBlank() ? (String) u.get("email") : n);
            }
        }
        Map<String, Level> levels = workspaces.levels();
        for (Map<String, Object> r : rows) {
            r.remove("searchVector");
            r.remove("contentText");
            String id = (String) r.get("id");
            r.put("tags", tags.getOrDefault(id, List.of()));
            r.put("links", links.getOrDefault(id, List.of()));
            r.put("ownerName", names.get((String) r.get("ownerUserId")));
            r.put("lockedByName", names.get((String) r.get("lockedById")));
            r.put("createdByName", names.get((String) r.get("createdById")));
            Level l = levels.getOrDefault((String) r.get("workspaceId"), Level.NONE);
            r.put("access", l.name().toLowerCase());
            r.put("readOnly", "linked".equals(r.get("sourceKind")));
        }
        return rows;
    }

    Map<String, Object> shapeOne(String id) {
        Map<String, Object> d = raw(id);
        return shape(new ArrayList<>(List.of(d))).getFirst();
    }

    @Transactional
    public Map<String, Object> get(String id) {
        readable(id);
        Map<String, Object> d = shapeOne(id);
        d.put("versions", versionsOf(id));
        return d;
    }

    @Transactional
    public PageResult<Map<String, Object>> list(ListFilter f) {
        Map<String, Level> levels = workspaces.levels();
        List<String> visible = levels.entrySet().stream().filter(e -> e.getValue() != Level.NONE)
                .map(Map.Entry::getKey).toList();
        int page = Math.max(1, f.page());
        int size = Math.min(200, Math.max(1, f.pageSize()));
        if (visible.isEmpty() && f.resType() == null) {
            return PageResult.of(List.of(), page, size, 0);
        }
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = :org");
        Map<String, Object> p = new HashMap<>();
        p.put("org", org());
        if (f.resType() != null && f.resId() != null) {
            DmsResourceProvider prov = resources.get(f.resType());
            if (!DmsResourceRegistry.canRead(prov)) {
                throw ApiException.forbidden("Missing required permission(s): " + prov.readPermission());
            }
            where.append(" AND EXISTS (SELECT 1 FROM \"DmsDocumentLink\" l WHERE l.\"documentId\" = d.id AND"
                    + " l.\"resType\" = :rt AND l.\"resId\" = :rid)");
            p.put("rt", f.resType());
            p.put("rid", f.resId());
        } else {
            where.append(" AND d.\"workspaceId\" IN (:ws)");
            p.put("ws", visible);
            if (f.resType() != null) {
                where.append(" AND EXISTS (SELECT 1 FROM \"DmsDocumentLink\" l WHERE l.\"documentId\" = d.id AND"
                        + " l.\"resType\" = :rt)");
                p.put("rt", f.resType());
            }
        }
        if (f.workspaceId() != null) {
            where.append(" AND d.\"workspaceId\" = :wsid");
            p.put("wsid", f.workspaceId());
        }
        if (f.folderId() != null) {
            where.append(" AND d.\"folderId\" = :fid");
            p.put("fid", f.folderId());
        } else if (f.rootOnly()) {
            where.append(" AND d.\"folderId\" IS NULL");
        }
        if (f.tagId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM \"DmsDocumentTag\" t WHERE t.\"documentId\" = d.id AND"
                    + " t.\"tagId\" = :tag)");
            p.put("tag", f.tagId());
        }
        if (f.status() != null) {
            if (!STATUSES.contains(f.status())) {
                throw ApiException.badRequest("status must be one of: active, approved, archived, pending_approval,"
                        + " rejected");
            }
            where.append(" AND d.status = :st");
            p.put("st", f.status());
        } else if (!f.deleted()) {
            where.append(" AND d.status <> 'archived'");
        }
        if (f.deleted()) {
            if (!TenantContext.has(Permissions.DMS__DOCUMENT_DELETE)) {
                throw ApiException.forbidden("Missing required permission(s): " + Permissions.DMS__DOCUMENT_DELETE);
            }
            where.append(" AND d.\"deletedAt\" IS NOT NULL");
        } else {
            where.append(" AND d.\"deletedAt\" IS NULL");
        }
        if (f.expiringBefore() != null) {
            where.append(" AND d.\"expiresOn\" IS NOT NULL AND d.\"expiresOn\" <= :exp");
            p.put("exp", java.sql.Date.valueOf(f.expiringBefore()));
        }
        if (f.mine()) {
            where.append(" AND (d.\"ownerUserId\" = :me OR d.\"createdById\" = :me)");
            p.put("me", TenantContext.userId());
        }
        String rank = "";
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (d.\"searchVector\" @@ websearch_to_tsquery('simple', :q) OR d.name ILIKE :like"
                    + " OR EXISTS (SELECT 1 FROM \"DmsDocumentTag\" qt JOIN \"DmsTag\" t ON t.id = qt.\"tagId\""
                    + " WHERE qt.\"documentId\" = d.id AND t.name ILIKE :like))");
            p.put("q", f.q().trim());
            p.put("like", "%" + f.q().trim().replace("%", "\\%").replace("_", "\\_") + "%");
            rank = ", ts_rank(d.\"searchVector\", websearch_to_tsquery('simple', :q)) AS rank,"
                    + " ts_headline('simple', coalesce(left(d.\"contentText\", 100000), ''),"
                    + " websearch_to_tsquery('simple', :q), 'MaxFragments=1,MaxWords=18,MinWords=6,StartSel=«,StopSel=»')"
                    + " AS snippet";
        }
        String order = switch (f.sort() == null ? "" : f.sort()) {
            case "name" -> " ORDER BY lower(d.name), d.id";
            case "size" -> " ORDER BY d.\"byteSize\" DESC, d.id";
            case "expiry" -> " ORDER BY d.\"expiresOn\" NULLS LAST, d.id";
            case "oldest" -> " ORDER BY d.\"createdAt\", d.id";
            default -> rank.isEmpty() ? " ORDER BY d.\"updatedAt\" DESC, d.id" : " ORDER BY rank DESC, d.\"updatedAt\" DESC";
        };
        long total = jdbc.sql("SELECT count(*) FROM \"DmsDocument\" d" + where).params(p).query(Long.class).single();
        p.put("lim", size);
        p.put("off", (page - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT d.*" + rank + " FROM \"DmsDocument\" d" + where
                + order + " LIMIT :lim OFFSET :off").params(p));
        Map<String, Object> snippets = new HashMap<>();
        for (Map<String, Object> r : rows) {
            r.remove("rank");
            Object s = r.remove("snippet");
            if (s != null && !s.toString().isBlank()) {
                snippets.put((String) r.get("id"), s);
            }
        }
        List<Map<String, Object>> out = shape(rows);
        out.forEach(r -> r.put("snippet", snippets.get((String) r.get("id"))));
        return PageResult.of(out, page, size, total);
    }

    // ---- writing ----

    private void activity(String documentId, String action, Map<String, Object> detail) {
        jdbc.sql("INSERT INTO \"DmsDocumentActivity\" (id, \"organizationId\", \"documentId\", action, detail,"
                + " \"actorId\") VALUES (?, ?, ?, ?, ?::jsonb, ?)")
                .params(UUID.randomUUID().toString(), org(), documentId, action,
                        detail == null ? null : json.writeValueAsString(detail), TenantContext.userId())
                .update();
    }

    private void event(String name, String documentId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", org());
        payload.put("documentId", documentId);
        if (extra != null) {
            payload.putAll(extra);
        }
        outbox.publish(name, payload);
    }

    private void validateUpload(FilePart f) {
        if (f.bytes() == null || f.bytes().length == 0) {
            throw ApiException.badRequest("Empty file");
        }
    }

    /** Resolve where an upload lands and whether the caller may put it there; returns the workspace id. */
    private String targetWorkspace(UploadInput in, DmsResourceProvider prov) {
        String ws = in.workspaceId();
        if (ws == null) {
            ws = workspaces.idOfCode(prov == null ? "general" : prov.workspaceCode());
        } else {
            workspaces.row(ws);
        }
        boolean wsWrite = workspaces.level(ws).ordinal() >= Level.WRITE.ordinal();
        boolean recordWrite = prov != null && DmsResourceRegistry.canRead(prov) && DmsResourceRegistry.canWrite(prov);
        if (!wsWrite && !recordWrite) {
            throw ApiException.forbidden("You do not have write access to this workspace");
        }
        if (in.folderId() != null) {
            Map<String, Object> folder = workspaces.folder(in.folderId());
            if (!ws.equals(folder.get("workspaceId"))) {
                throw ApiException.badRequest("Folder belongs to another workspace");
            }
        }
        return ws;
    }

    /** Upload one or more files as new documents. */
    @Transactional
    public List<Map<String, Object>> upload(UploadInput in, List<FilePart> parts) {
        if (parts == null || parts.isEmpty()) {
            throw ApiException.badRequest("Missing file");
        }
        if (parts.size() > 20) {
            throw ApiException.badRequest("At most 20 files per upload");
        }
        DmsResourceProvider prov = null;
        if (in.resType() != null || in.resId() != null) {
            prov = resources.requireRecord(in.resType(), in.resId());
            if (!DmsResourceRegistry.canRead(prov) || !DmsResourceRegistry.canWrite(prov)) {
                throw ApiException.forbidden("Missing required permission(s): " + prov.writePermission());
            }
        }
        String ws = targetWorkspace(in, prov);
        if (in.reminderLeadDays() != null && (in.reminderLeadDays() < 0 || in.reminderLeadDays() > 365)) {
            throw ApiException.badRequest("reminderLeadDays must be between 0 and 365");
        }
        List<String> tagIds = in.tags() == null ? List.of() : workspaces.tagIdsForNames(in.tags());
        List<Map<String, Object>> out = new ArrayList<>();
        for (FilePart part : parts) {
            validateUpload(part);
            out.add(createDocument(ws, in.folderId(), part, in, tagIds, prov == null ? null : in.resType(),
                    prov == null ? null : in.resId(), "native"));
        }
        return out;
    }

    private Map<String, Object> createDocument(String ws, String folderId, FilePart part, UploadInput in,
            List<String> tagIds, String resType, String resId, String sourceKind) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> stored = files.upload(part.filename(), part.contentType(), part.bytes(), OWNER_TYPE, id);
        String fileId = (String) stored.get("id");
        Map<String, Object> wsRow = workspaces.row(ws);
        boolean needsApproval = Boolean.TRUE.equals(wsRow.get("requiresApproval"));
        String name = part.filename() == null || part.filename().isBlank() ? "file" : part.filename().trim();
        jdbc.sql("""
                INSERT INTO "DmsDocument" (id, "organizationId", "workspaceId", "folderId", name, description, "fileId",
                  "contentType", "byteSize", "ownerUserId", "partnerId", status, "expiresOn", "reminderLeadDays",
                  "sourceKind", "createdById")
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, org(), ws, folderId, name, in == null ? null : in.description(), fileId,
                        part.contentType(), part.bytes().length, TenantContext.userId(),
                        in == null ? null : in.partnerId(), needsApproval ? "pending_approval" : "active",
                        in == null || in.expiresOn() == null ? null : java.sql.Date.valueOf(in.expiresOn()),
                        in == null || in.reminderLeadDays() == null ? 30 : in.reminderLeadDays(), sourceKind,
                        TenantContext.userId())
                .update();
        jdbc.sql("""
                INSERT INTO "DmsDocumentVersion" (id, "organizationId", "documentId", "versionNo", "fileId", filename,
                  "contentType", "byteSize", "uploadedById") VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID().toString(), org(), id, fileId, name, part.contentType(), part.bytes().length,
                        TenantContext.userId())
                .update();
        for (String tag : tagIds) {
            jdbc.sql("INSERT INTO \"DmsDocumentTag\" (\"documentId\", \"tagId\") VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(id, tag).update();
        }
        if (resType != null) {
            insertLink(id, resType, resId);
        }
        activity(id, "uploaded", Map.of("filename", name, "byteSize", part.bytes().length));
        audit.recordInTx(AuditInput.of("DmsDocument", id, "create", null, Map.of("name", name, "workspaceId", ws)));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("workspaceId", ws);
        ev.put("name", name);
        if (resType != null) {
            ev.put("resType", resType);
            ev.put("resId", resId);
        }
        event(DomainEvents.DMS_DOCUMENT_UPLOADED, id, ev);
        if (needsApproval) {
            requestApproval(id, name, ws);
        }
        extractor.extractAfterCommit(id);
        return shapeOne(id);
    }

    private void insertLink(String documentId, String resType, String resId) {
        jdbc.sql("INSERT INTO \"DmsDocumentLink\" (id, \"organizationId\", \"documentId\", \"resType\", \"resId\","
                + " \"createdById\") VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
                .params(UUID.randomUUID().toString(), org(), documentId, resType, resId, TenantContext.userId())
                .update();
    }

    /** A new version of the file. */
    @Transactional
    public Map<String, Object> addVersion(String id, FilePart part, String note) {
        Map<String, Object> d = writable(id);
        validateUpload(part);
        Map<String, Object> stored = files.upload(part.filename(), part.contentType(), part.bytes(), OWNER_TYPE, id);
        int next = ((Number) d.get("currentVersion")).intValue() + 1;
        String fileId = (String) stored.get("id");
        jdbc.sql("""
                INSERT INTO "DmsDocumentVersion" (id, "organizationId", "documentId", "versionNo", "fileId", filename,
                  "contentType", "byteSize", note, "uploadedById") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID().toString(), org(), id, next, fileId,
                        part.filename() == null ? d.get("name") : part.filename(), part.contentType(),
                        part.bytes().length, note, TenantContext.userId())
                .update();
        setCurrentFile(id, fileId, part.contentType(), part.bytes().length, next);
        activity(id, "versioned", Map.of("versionNo", next, "filename", String.valueOf(part.filename())));
        event(DomainEvents.DMS_DOCUMENT_VERSIONED, id, Map.of("versionNo", next));
        extractor.extractAfterCommit(id);
        return get(id);
    }

    private void setCurrentFile(String id, String fileId, String contentType, long size, int versionNo) {
        // a renewal (new version) re-arms the expiry reminders
        jdbc.sql("""
                UPDATE "DmsDocument" SET "fileId" = ?, "contentType" = ?, "byteSize" = ?, "currentVersion" = ?,
                  "textStatus" = 'pending', "contentText" = NULL, "expiryNotifiedAt" = NULL, "expiredNotifiedAt" = NULL,
                  version = version + 1, "updatedAt" = now()
                WHERE id = ?""")
                .params(fileId, contentType, size, versionNo, id).update();
    }

    @Transactional
    public List<Map<String, Object>> versions(String id) {
        readable(id);
        return versionsOf(id);
    }

    private List<Map<String, Object>> versionsOf(String id) {
        return SqlRows.list(jdbc.sql("""
                SELECT v.*, trim(coalesce(u."firstName", '') || ' ' || coalesce(u."lastName", '')) AS "uploadedByName"
                FROM "DmsDocumentVersion" v LEFT JOIN "User" u ON u.id = v."uploadedById"
                WHERE v."documentId" = ? ORDER BY v."versionNo" DESC""").param(id));
    }

    /** Make an old version current again (recorded as a new version pointing at the old file). */
    @Transactional
    public Map<String, Object> restoreVersion(String id, int versionNo) {
        Map<String, Object> d = writable(id);
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT * FROM \"DmsDocumentVersion\" WHERE \"documentId\" = ? AND"
                + " \"versionNo\" = ?").params(id, versionNo));
        if (v == null) {
            throw ApiException.notFound("Version not found");
        }
        int next = ((Number) d.get("currentVersion")).intValue() + 1;
        jdbc.sql("""
                INSERT INTO "DmsDocumentVersion" (id, "organizationId", "documentId", "versionNo", "fileId", filename,
                  "contentType", "byteSize", note, "uploadedById") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID().toString(), org(), id, next, v.get("fileId"), v.get("filename"),
                        v.get("contentType"), v.get("byteSize"), "Restored version " + versionNo, TenantContext.userId())
                .update();
        setCurrentFile(id, (String) v.get("fileId"), (String) v.get("contentType"),
                ((Number) v.get("byteSize")).longValue(), next);
        activity(id, "version_restored", Map.of("restored", versionNo, "versionNo", next));
        event(DomainEvents.DMS_DOCUMENT_VERSIONED, id, Map.of("versionNo", next, "restoredFrom", versionNo));
        extractor.extractAfterCommit(id);
        return get(id);
    }

    /** Patch metadata. */
    public record UpdateInput(String name, String description, String folderId, boolean setFolder, String workspaceId,
            LocalDate expiresOn, boolean setExpiry, Integer reminderLeadDays, String partnerId, List<String> tags,
            Integer version) {}

    @Transactional
    public Map<String, Object> update(String id, UpdateInput in) {
        Map<String, Object> d = writable(id);
        if (in.version() != null && ((Number) d.get("version")).intValue() != in.version()) {
            throw ApiException.conflict("Document was changed by someone else; reload and try again");
        }
        String ws = (String) d.get("workspaceId");
        boolean moved = false;
        if (in.workspaceId() != null && !in.workspaceId().equals(ws)) {
            workspaces.row(in.workspaceId());
            workspaces.require(in.workspaceId(), Level.WRITE);
            ws = in.workspaceId();
            moved = true;
        }
        String folder = (String) d.get("folderId");
        if (in.setFolder() || moved) {
            folder = in.setFolder() ? in.folderId() : null;
            if (folder != null && !ws.equals(workspaces.folder(folder).get("workspaceId"))) {
                throw ApiException.badRequest("Folder belongs to another workspace");
            }
            moved = moved || !java.util.Objects.equals(folder, d.get("folderId"));
        }
        if (in.name() != null && in.name().isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        if (in.reminderLeadDays() != null && (in.reminderLeadDays() < 0 || in.reminderLeadDays() > 365)) {
            throw ApiException.badRequest("reminderLeadDays must be between 0 and 365");
        }
        jdbc.sql("""
                UPDATE "DmsDocument" SET name = coalesce(?, name), description = coalesce(?, description),
                  "workspaceId" = ?, "folderId" = ?,
                  "expiresOn" = CASE WHEN ? THEN ? ELSE "expiresOn" END,
                  "expiryNotifiedAt" = CASE WHEN ? THEN NULL ELSE "expiryNotifiedAt" END,
                  "expiredNotifiedAt" = CASE WHEN ? THEN NULL ELSE "expiredNotifiedAt" END,
                  "reminderLeadDays" = coalesce(?, "reminderLeadDays"), "partnerId" = coalesce(?, "partnerId"),
                  version = version + 1, "updatedAt" = now()
                WHERE id = ?""")
                .params(in.name() == null ? null : in.name().trim(), in.description(), ws, folder, in.setExpiry(),
                        in.expiresOn() == null ? null : java.sql.Date.valueOf(in.expiresOn()), in.setExpiry(),
                        in.setExpiry(), in.reminderLeadDays(), in.partnerId(), id)
                .update();
        if (in.tags() != null) {
            setTags(id, in.tags());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        if (in.name() != null && !in.name().trim().equals(d.get("name"))) {
            detail.put("renamed", in.name().trim());
        }
        if (moved) {
            detail.put("workspaceId", ws);
            detail.put("folderId", folder);
        }
        if (in.setExpiry()) {
            detail.put("expiresOn", in.expiresOn() == null ? null : in.expiresOn().toString());
        }
        if (in.tags() != null) {
            detail.put("tags", in.tags());
        }
        activity(id, moved ? "moved" : "updated", detail);
        audit.recordInTx(AuditInput.of("DmsDocument", id, "update", Map.of("name", d.get("name")), detail));
        if (moved) {
            event(DomainEvents.DMS_DOCUMENT_MOVED, id, Map.of("workspaceId", ws));
        }
        return get(id);
    }

    private void setTags(String id, List<String> tagIds) {
        for (String t : tagIds) {
            if (jdbc.sql("SELECT count(*) FROM \"DmsTag\" WHERE id = ? AND \"organizationId\" = ?").params(t, org())
                    .query(Long.class).single() == 0) {
                throw ApiException.badRequest("Tag not found: " + t);
            }
        }
        jdbc.sql("DELETE FROM \"DmsDocumentTag\" WHERE \"documentId\" = ?").param(id).update();
        for (String t : new LinkedHashSet<>(tagIds)) {
            jdbc.sql("INSERT INTO \"DmsDocumentTag\" (\"documentId\", \"tagId\") VALUES (?, ?)").params(id, t).update();
        }
    }

    @Transactional
    public Map<String, Object> link(String id, String resType, String resId) {
        writable(id);
        DmsResourceProvider p = resources.requireRecord(resType, resId);
        if (!DmsResourceRegistry.canRead(p) || !DmsResourceRegistry.canWrite(p)) {
            throw ApiException.forbidden("Missing required permission(s): " + p.writePermission());
        }
        try {
            jdbc.sql("INSERT INTO \"DmsDocumentLink\" (id, \"organizationId\", \"documentId\", \"resType\", \"resId\","
                    + " \"createdById\") VALUES (?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, resType, resId, TenantContext.userId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Document is already linked to this record");
        }
        activity(id, "linked", Map.of("resType", resType, "resId", resId));
        event(DomainEvents.DMS_DOCUMENT_LINKED, id, Map.of("resType", resType, "resId", resId));
        return get(id);
    }

    @Transactional
    public Map<String, Object> unlink(String id, String linkId) {
        writable(id);
        Map<String, Object> l = SqlRows.one(jdbc.sql("SELECT * FROM \"DmsDocumentLink\" WHERE id = ? AND"
                + " \"documentId\" = ?").params(linkId, id));
        if (l == null) {
            throw ApiException.notFound("Link not found");
        }
        DmsResourceProvider p = resources.find((String) l.get("resType"));
        if (p != null && !DmsResourceRegistry.canWrite(p)) {
            throw ApiException.forbidden("Missing required permission(s): " + p.writePermission());
        }
        jdbc.sql("DELETE FROM \"DmsDocumentLink\" WHERE id = ?").param(linkId).update();
        activity(id, "unlinked", Map.of("resType", l.get("resType"), "resId", l.get("resId")));
        return get(id);
    }

    @Transactional
    public Map<String, Object> lock(String id, boolean lock) {
        Map<String, Object> d = writable(id);
        if (lock && d.get("lockedById") != null) {
            return get(id);
        }
        jdbc.sql("UPDATE \"DmsDocument\" SET \"lockedById\" = ?, \"lockedAt\" = CASE WHEN ? THEN now() ELSE NULL END,"
                + " \"updatedAt\" = now() WHERE id = ?")
                .params(lock ? TenantContext.userId() : null, lock, id).update();
        activity(id, lock ? "locked" : "unlocked", null);
        return get(id);
    }

    @Transactional
    public Map<String, Object> archive(String id, boolean archive) {
        Map<String, Object> d = writable(id);
        if (archive == "archived".equals(d.get("status"))) {
            return get(id);
        }
        jdbc.sql("UPDATE \"DmsDocument\" SET status = ?, \"updatedAt\" = now(), version = version + 1 WHERE id = ?")
                .params(archive ? "archived" : "active", id).update();
        activity(id, archive ? "archived" : "unarchived", null);
        return get(id);
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> d = writable(id);
        jdbc.sql("UPDATE \"DmsDocument\" SET \"deletedAt\" = now(), \"updatedAt\" = now() WHERE id = ?").param(id)
                .update();
        activity(id, "deleted", null);
        audit.recordInTx(AuditInput.of("DmsDocument", id, "delete", Map.of("name", d.get("name")), null));
        event(DomainEvents.DMS_DOCUMENT_DELETED, id, Map.of("name", d.get("name")));
        return Map.of("deleted", true);
    }

    @Transactional
    public Map<String, Object> restore(String id) {
        Map<String, Object> d = readable(id);
        if (d.get("deletedAt") == null) {
            return get(id);
        }
        if ("linked".equals(d.get("sourceKind"))) {
            throw ApiException.badRequest("This file is managed by its module and is read-only here");
        }
        workspaces.require((String) d.get("workspaceId"), Level.WRITE);
        jdbc.sql("UPDATE \"DmsDocument\" SET \"deletedAt\" = NULL, \"updatedAt\" = now() WHERE id = ?").param(id)
                .update();
        activity(id, "restored", null);
        return get(id);
    }

    /** Bulk action result. */
    public record BulkInput(List<String> ids, String action, String workspaceId, String folderId, List<String> tagIds) {}

    @Transactional
    public Map<String, Object> bulk(BulkInput in) {
        if (in.ids() == null || in.ids().isEmpty()) {
            throw ApiException.badRequest("ids should not be empty");
        }
        if (in.ids().size() > 200) {
            throw ApiException.badRequest("At most 200 documents per bulk action");
        }
        int done = 0;
        for (String id : new LinkedHashSet<>(in.ids())) {
            switch (in.action() == null ? "" : in.action()) {
                case "move" -> update(id, new UpdateInput(null, null, in.folderId(), true, in.workspaceId(), null,
                        false, null, null, null, null));
                case "tag" -> {
                    writable(id);
                    for (String t : in.tagIds() == null ? List.<String>of() : in.tagIds()) {
                        if (jdbc.sql("SELECT count(*) FROM \"DmsTag\" WHERE id = ? AND \"organizationId\" = ?")
                                .params(t, org()).query(Long.class).single() == 0) {
                            throw ApiException.badRequest("Tag not found: " + t);
                        }
                        jdbc.sql("INSERT INTO \"DmsDocumentTag\" (\"documentId\", \"tagId\") VALUES (?, ?) ON CONFLICT"
                                + " DO NOTHING").params(id, t).update();
                    }
                    activity(id, "tagged", Map.of("tagIds", in.tagIds() == null ? List.of() : in.tagIds()));
                }
                case "archive" -> archive(id, true);
                case "unarchive" -> archive(id, false);
                case "delete" -> {
                    if (!TenantContext.has(Permissions.DMS__DOCUMENT_DELETE)) {
                        throw ApiException.forbidden("Missing required permission(s): " + Permissions.DMS__DOCUMENT_DELETE);
                    }
                    delete(id);
                }
                default -> throw ApiException.badRequest("action must be one of: archive, delete, move, tag, unarchive");
            }
            done++;
        }
        return Map.of("updated", done);
    }

    /** Signed download / preview URLs of the current (or a given) version. */
    @Transactional
    public Map<String, Object> signedUrl(String id, Integer versionNo) {
        Map<String, Object> d = readable(id);
        String fileId = (String) d.get("fileId");
        String contentType = (String) d.get("contentType");
        if (versionNo != null) {
            Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT * FROM \"DmsDocumentVersion\" WHERE \"documentId\" = ?"
                    + " AND \"versionNo\" = ?").params(id, versionNo));
            if (v == null) {
                throw ApiException.notFound("Version not found");
            }
            fileId = (String) v.get("fileId");
            contentType = (String) v.get("contentType");
        }
        Map<String, Object> signed = new LinkedHashMap<>(files.signDownload(fileId));
        boolean previewable = contentType != null && FilesService.INLINE_TYPES.contains(contentType.toLowerCase());
        signed.put("previewUrl", previewable ? signed.get("url") + "&inline=true" : null);
        signed.put("contentType", contentType);
        signed.put("filename", d.get("name"));
        return signed;
    }

    @Transactional
    public List<Map<String, Object>> timeline(String id) {
        readable(id);
        return SqlRows.list(jdbc.sql("""
                SELECT a.id, a.action, a.detail, a."actorId", a."createdAt",
                  trim(coalesce(u."firstName", '') || ' ' || coalesce(u."lastName", '')) AS "actorName"
                FROM "DmsDocumentActivity" a LEFT JOIN "User" u ON u.id = a."actorId"
                WHERE a."documentId" = ? ORDER BY a."createdAt" DESC, a.id""").param(id));
    }

    // ---- approval ----

    private void requestApproval(String id, String name, String ws) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", name);
        snapshot.put("workspaceId", ws);
        Map<String, Object> req = approvals.requestApproval(APPROVAL_ENTITY, id, snapshot, null);
        if (req != null) {
            jdbc.sql("UPDATE \"DmsDocument\" SET \"approvalRequestId\" = ? WHERE id = ?").params(req.get("id"), id)
                    .update();
        }
        activity(id, "approval_requested", req == null ? Map.of("engine", false) : Map.of("requestId", req.get("id")));
    }

    /** Submit an active document for approval. */
    @Transactional
    public Map<String, Object> submit(String id) {
        Map<String, Object> d = writable(id);
        if (!"active".equals(d.get("status")) && !"rejected".equals(d.get("status"))) {
            throw ApiException.badRequest("Only active or rejected documents can be submitted for approval");
        }
        jdbc.sql("UPDATE \"DmsDocument\" SET status = 'pending_approval', \"updatedAt\" = now() WHERE id = ?").param(id)
                .update();
        requestApproval(id, (String) d.get("name"), (String) d.get("workspaceId"));
        return get(id);
    }

    /** Manual decision (approver with dms:document:approve). */
    @Transactional
    public Map<String, Object> decide(String id, boolean approve, String comment) {
        Map<String, Object> d = readable(id);
        if (!"pending_approval".equals(d.get("status"))) {
            throw ApiException.badRequest("Document is not waiting for approval");
        }
        if (d.get("approvalRequestId") != null) {
            approvals.decide((String) d.get("approvalRequestId"), approve ? "approved" : "rejected", comment);
            // the engine's approval.decided event applies the final outcome (multi-step workflows may continue)
            return get(id);
        }
        applyDecision(id, approve, comment);
        return get(id);
    }

    /** Apply a final approval outcome (idempotent; only a pending document changes). */
    @Transactional
    public void applyDecision(String id, boolean approved, String comment) {
        int n = jdbc.sql("UPDATE \"DmsDocument\" SET status = ?, \"updatedAt\" = now(), version = version + 1 WHERE"
                + " id = ? AND \"organizationId\" = ? AND status = 'pending_approval'")
                .params(approved ? "approved" : "rejected", id, org()).update();
        if (n == 0) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        if (comment != null) {
            detail.put("comment", comment);
        }
        activity(id, approved ? "approved" : "rejected", detail);
        event(approved ? DomainEvents.DMS_DOCUMENT_APPROVED : DomainEvents.DMS_DOCUMENT_REJECTED, id, detail);
    }

    // ---- record attachments ----

    @Transactional
    public List<Map<String, Object>> recordAttachments(String resType, String resId) {
        DmsResourceProvider p = resources.requireRecord(resType, resId);
        if (!DmsResourceRegistry.canRead(p)) {
            throw ApiException.forbidden("Missing required permission(s): " + p.readPermission());
        }
        return shape(SqlRows.list(jdbc.sql("""
                SELECT d.* FROM "DmsDocument" d JOIN "DmsDocumentLink" l ON l."documentId" = d.id
                WHERE l."organizationId" = ? AND l."resType" = ? AND l."resId" = ? AND d."deletedAt" IS NULL
                ORDER BY d."createdAt" DESC""").params(org(), resType, resId)));
    }

    @Transactional
    public Map<String, Object> recordCount(String resType, String resId) {
        DmsResourceProvider p = resources.get(resType);
        if (!DmsResourceRegistry.canRead(p)) {
            throw ApiException.forbidden("Missing required permission(s): " + p.readPermission());
        }
        long n = jdbc.sql("""
                SELECT count(*) FROM "DmsDocument" d JOIN "DmsDocumentLink" l ON l."documentId" = d.id
                WHERE l."organizationId" = ? AND l."resType" = ? AND l."resId" = ? AND d."deletedAt" IS NULL""")
                .params(org(), resType, resId).query(Long.class).single();
        return Map.of("count", n);
    }

    /** Record types documents can be linked to. */
    public List<Map<String, Object>> resourceTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DmsResourceProvider p : resources.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resType", p.resType());
            m.put("label", p.label());
            m.put("workspaceCode", p.workspaceCode());
            m.put("readable", DmsResourceRegistry.canRead(p));
            out.add(m);
        }
        return out;
    }

    // ---- DmsAttachments (internal API for other modules) ----

    @Override
    @Transactional
    public Map<String, Object> attach(String resType, String resId, String workspaceCode, String filename,
            String contentType, byte[] bytes, List<String> tags) {
        DmsResourceProvider p = resources.requireRecord(resType, resId);
        String ws = workspaces.idOfCode(workspaceCode == null ? p.workspaceCode() : workspaceCode);
        List<String> tagIds = tags == null ? List.of() : workspaces.tagIdsForNames(tags);
        return createDocument(ws, null, new FilePart(filename, contentType, bytes), null, tagIds, resType, resId,
                "native");
    }

    @Override
    @Transactional
    public Map<String, Object> attachInFolder(String resType, String resId, String workspaceCode, String folderPath,
            String filename, String contentType, byte[] bytes, List<String> tags) {
        resources.requireRecord(resType, resId);
        String ws = workspaces.idOfCode(workspaceCode);
        String folder = folderPath == null ? null : workspaces.ensureFolderPath(ws, folderPath);
        List<String> tagIds = tags == null ? List.of() : workspaces.tagIdsForNames(tags);
        return createDocument(ws, folder, new FilePart(filename, contentType, bytes), null, tagIds, resType, resId,
                "native");
    }

    @Override
    public Map<String, String> describe(String resType, String resId) {
        DmsResourceProvider p = resources.find(resType);
        if (p == null || resId == null) {
            return null;
        }
        String label = p.displayName(resId);
        if (label == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("webPath", p.webPath(resId));
        out.put("typeLabel", p.label());
        return out;
    }

    @Override
    @Transactional
    public List<Map<String, Object>> list(String resType, String resId) {
        return shape(SqlRows.list(jdbc.sql("""
                SELECT d.* FROM "DmsDocument" d JOIN "DmsDocumentLink" l ON l."documentId" = d.id
                WHERE l."organizationId" = ? AND l."resType" = ? AND l."resId" = ? AND d."deletedAt" IS NULL
                ORDER BY d."createdAt" DESC""").params(org(), resType, resId)));
    }

    /** Internal create without access checks (document requests: the request grants the upload). */
    @Transactional
    public Map<String, Object> createInternal(String workspaceId, FilePart part, String resType, String resId) {
        validateUpload(part);
        if (resType != null) {
            resources.requireRecord(resType, resId);
        }
        return createDocument(workspaceId, null, part, null, List.of(), resType, resId, "native");
    }

    /** Create a document from a public share-link upload (no user; lands pending in the target workspace). */
    @Transactional
    public Map<String, Object> createFromShare(String workspaceId, String folderId, FilePart part, String uploader) {
        validateUpload(part);
        Map<String, Object> created = createDocument(workspaceId, folderId, part,
                new UploadInput(workspaceId, folderId, null, null, null, uploader == null ? "Uploaded through a share"
                        + " link" : "Uploaded through a share link by " + uploader, null, null, null),
                List.of(), null, null, "native");
        return created;
    }
}
