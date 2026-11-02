package com.inventoryerp.dms.application;

import com.inventoryerp.dms.application.DmsDocumentService.FilePart;
import com.inventoryerp.dms.application.DmsWorkspaceService.Level;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.files.FilesService.Download;
import com.inventoryerp.kernel.files.FilesService.FileMeta;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * External share links: a random 256-bit token (only its SHA-256 is stored) for a set of documents or a folder, with
 * expiry, optional password (PBKDF2), download limit and optional upload. Public routes resolve the token, run inside
 * the link's organization and never reveal other documents.
 */
@Service
public class DmsShareService {

    static final int MAX_DAYS = 365;
    static final int MAX_UPLOADS = 100;

    private final JdbcClient jdbc;
    private final DmsDocumentService documents;
    private final DmsWorkspaceService workspaces;
    private final FilesService files;
    private final EventOutboxService outbox;
    private final String publicWebUrl;
    private final SecureRandom random = new SecureRandom();

    public DmsShareService(JdbcClient jdbc, DmsDocumentService documents, DmsWorkspaceService workspaces,
            FilesService files, EventOutboxService outbox,
            @Value("${APP_PUBLIC_WEB_URL:http://localhost:5173}") String publicWebUrl) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.workspaces = workspaces;
        this.files = files;
        this.outbox = outbox;
        this.publicWebUrl = publicWebUrl.replaceAll("/$", "");
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    public record ShareInput(String name, List<String> documentIds, String workspaceId, String folderId,
            Boolean allowDownload, Boolean allowUpload, String password, Integer expiresInDays, Integer maxDownloads) {}

    @Transactional
    public Map<String, Object> create(ShareInput in) {
        boolean folderScope = in.documentIds() == null || in.documentIds().isEmpty();
        int days = in.expiresInDays() == null ? 7 : in.expiresInDays();
        if (days < 1 || days > MAX_DAYS) {
            throw ApiException.badRequest("expiresInDays must be between 1 and " + MAX_DAYS);
        }
        if (in.maxDownloads() != null && in.maxDownloads() < 1) {
            throw ApiException.badRequest("maxDownloads must be at least 1");
        }
        if (in.password() != null && !in.password().isEmpty() && in.password().length() < 6) {
            throw ApiException.badRequest("password must be at least 6 characters");
        }
        String ws = in.workspaceId();
        if (folderScope) {
            if (ws == null) {
                throw ApiException.badRequest("Share either documentIds or a workspaceId / folderId");
            }
            workspaces.row(ws);
            workspaces.require(ws, Boolean.TRUE.equals(in.allowUpload()) ? Level.WRITE : Level.READ);
            if (in.folderId() != null && !ws.equals(workspaces.folder(in.folderId()).get("workspaceId"))) {
                throw ApiException.badRequest("Folder belongs to another workspace");
            }
        } else {
            if (in.documentIds().size() > 100) {
                throw ApiException.badRequest("At most 100 documents per share link");
            }
            if (Boolean.TRUE.equals(in.allowUpload())) {
                throw ApiException.badRequest("Uploads are only possible on a folder share");
            }
            for (String id : in.documentIds()) {
                Map<String, Object> d = documents.readable(id);
                if (d.get("deletedAt") != null) {
                    throw ApiException.badRequest("Document is deleted: " + d.get("name"));
                }
            }
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String id = UUID.randomUUID().toString();
        Instant expires = Instant.now().plus(days, ChronoUnit.DAYS);
        jdbc.sql("""
                INSERT INTO "DmsShareLink" (id, "organizationId", "tokenHash", name, scope, "documentIds", "workspaceId",
                  "folderId", "allowDownload", "allowUpload", "passwordHash", "expiresAt", "maxDownloads", "createdById")
                VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, org(), sha256(token), in.name(), folderScope ? "folder" : "documents",
                        folderScope ? new String[0] : in.documentIds().toArray(String[]::new),
                        folderScope ? ws : null, folderScope ? in.folderId() : null,
                        in.allowDownload() == null || in.allowDownload(), Boolean.TRUE.equals(in.allowUpload()),
                        in.password() == null || in.password().isEmpty() ? null : hashPassword(in.password()),
                        SqlRows.ts(expires), in.maxDownloads(), TenantContext.userId())
                .update();
        Map<String, Object> out = row(id);
        out.put("token", token);
        out.put("url", publicWebUrl + "/share/" + token);
        return out;
    }

    private Map<String, Object> row(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"DmsShareLink\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Share link not found");
        }
        return shapeLink(r);
    }

    private static Map<String, Object> shapeLink(Map<String, Object> r) {
        r.remove("tokenHash");
        r.put("hasPassword", r.remove("passwordHash") != null);
        Instant exp = (Instant) r.get("expiresAt");
        r.put("active", r.get("revokedAt") == null && exp != null && exp.isAfter(Instant.now()));
        return r;
    }

    @Transactional
    public List<Map<String, Object>> list(String documentId) {
        List<Map<String, Object>> rows;
        if (documentId != null) {
            documents.readable(documentId);
            rows = SqlRows.list(jdbc.sql("SELECT * FROM \"DmsShareLink\" WHERE \"organizationId\" = ? AND ? ="
                    + " ANY(\"documentIds\") ORDER BY \"createdAt\" DESC").params(org(), documentId));
        } else {
            rows = SqlRows.list(jdbc.sql("SELECT * FROM \"DmsShareLink\" WHERE \"organizationId\" = ? ORDER BY"
                    + " \"createdAt\" DESC LIMIT 500").param(org()));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        rows.forEach(r -> out.add(shapeLink(r)));
        return out;
    }

    @Transactional
    public Map<String, Object> revoke(String id) {
        row(id);
        jdbc.sql("UPDATE \"DmsShareLink\" SET \"revokedAt\" = coalesce(\"revokedAt\", now()) WHERE id = ?").param(id)
                .update();
        return row(id);
    }

    // ---- public side ----

    /** The live link of a token (404 for unknown, revoked or expired; 401 for a missing / wrong password). */
    Map<String, Object> resolve(String token, String password) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            throw ApiException.notFound("Share link not found or expired");
        }
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"DmsShareLink\" WHERE \"tokenHash\" = ?")
                .param(sha256(token)));
        if (r == null || r.get("revokedAt") != null || !((Instant) r.get("expiresAt")).isAfter(Instant.now())) {
            throw ApiException.notFound("Share link not found or expired");
        }
        String hash = (String) r.get("passwordHash");
        if (hash != null) {
            if (password == null || password.isEmpty()) {
                throw ApiException.forbidden("Password required");
            }
            if (!checkPassword(password, hash)) {
                throw ApiException.forbidden("Invalid password");
            }
        }
        return r;
    }

    private List<Map<String, Object>> scopedDocuments(Map<String, Object> link) {
        String sql = "SELECT id, name, \"contentType\", \"byteSize\", \"updatedAt\", \"fileId\" FROM \"DmsDocument\""
                + " WHERE \"organizationId\" = :org AND \"deletedAt\" IS NULL AND status IN ('active', 'approved')";
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("org", link.get("organizationId"));
        if ("documents".equals(link.get("scope"))) {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) link.get("documentIds");
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            sql += " AND id IN (:ids)";
            p.put("ids", ids);
        } else {
            sql += " AND \"workspaceId\" = :ws AND coalesce(\"folderId\", '') = :folder";
            p.put("ws", link.get("workspaceId"));
            p.put("folder", link.get("folderId") == null ? "" : link.get("folderId"));
        }
        return SqlRows.list(jdbc.sql(sql + " ORDER BY lower(name) LIMIT 500").params(p));
    }

    @Transactional
    public Map<String, Object> publicView(String token, String password) {
        Map<String, Object> link = resolve(token, password);
        jdbc.sql("UPDATE \"DmsShareLink\" SET \"lastAccessedAt\" = now() WHERE id = ?").param(link.get("id")).update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", link.get("name"));
        out.put("organizationName", jdbc.sql("SELECT name FROM \"Organization\" WHERE id = ?")
                .param(link.get("organizationId")).query(String.class).optional().orElse(null));
        out.put("expiresAt", link.get("expiresAt"));
        out.put("allowDownload", link.get("allowDownload"));
        out.put("allowUpload", link.get("allowUpload"));
        Integer max = (Integer) link.get("maxDownloads");
        out.put("downloadsRemaining", max == null ? null : Math.max(0, max - (Integer) link.get("downloadCount")));
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> d : scopedDocuments(link)) {
            d.remove("fileId");
            docs.add(d);
        }
        out.put("documents", docs);
        return out;
    }

    /** Stream one shared document; counts against the download limit. */
    @Transactional
    public Download publicDownload(String token, String password, String documentId) {
        Map<String, Object> link = resolve(token, password);
        if (!Boolean.TRUE.equals(link.get("allowDownload"))) {
            throw ApiException.forbidden("Downloads are disabled for this link");
        }
        Map<String, Object> doc = scopedDocuments(link).stream().filter(d -> d.get("id").equals(documentId))
                .findFirst().orElseThrow(() -> ApiException.notFound("Document not found"));
        int n = jdbc.sql("UPDATE \"DmsShareLink\" SET \"downloadCount\" = \"downloadCount\" + 1, \"lastAccessedAt\" ="
                + " now() WHERE id = ? AND (\"maxDownloads\" IS NULL OR \"downloadCount\" < \"maxDownloads\")")
                .param(link.get("id")).update();
        if (n == 0) {
            throw ApiException.forbidden("Download limit reached");
        }
        FileMeta meta = files.metaUnscoped((String) doc.get("fileId"));
        if (meta == null || !meta.organizationId().equals(link.get("organizationId"))) {
            throw ApiException.notFound("Document not found");
        }
        String org = (String) link.get("organizationId");
        TenantContext.run(TenantStore.organization(org), () -> outbox.publish(DomainEvents.DMS_SHARE_ACCESSED,
                Map.of("organizationId", org, "shareLinkId", link.get("id"), "documentId", documentId,
                        "action", "download")));
        return new Download((String) doc.get("name"), meta.contentType(), files.open(meta));
    }

    /** Upload into a folder share (lands pending approval in the shared workspace / folder). */
    @Transactional
    public List<Map<String, Object>> publicUpload(String token, String password, List<FilePart> parts,
            String uploaderName) {
        Map<String, Object> link = resolve(token, password);
        if (!Boolean.TRUE.equals(link.get("allowUpload"))) {
            throw ApiException.forbidden("Uploads are disabled for this link");
        }
        if (parts == null || parts.isEmpty()) {
            throw ApiException.badRequest("Missing file");
        }
        if (parts.size() > 10) {
            throw ApiException.badRequest("At most 10 files per upload");
        }
        int n = jdbc.sql("UPDATE \"DmsShareLink\" SET \"uploadCount\" = \"uploadCount\" + ? WHERE id = ? AND"
                + " \"uploadCount\" + ? <= ?").params(parts.size(), link.get("id"), parts.size(), MAX_UPLOADS).update();
        if (n == 0) {
            throw ApiException.forbidden("Upload limit reached");
        }
        String org = (String) link.get("organizationId");
        String uploader = uploaderName == null ? null : uploaderName.replaceAll("[\\r\\n]", " ").trim();
        if (uploader != null && uploader.length() > 120) {
            uploader = uploader.substring(0, 120);
        }
        String who = uploader;
        return TenantContext.run(TenantStore.organization(org), () -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (FilePart part : parts) {
                Map<String, Object> d = documents.createFromShare((String) link.get("workspaceId"),
                        (String) link.get("folderId"), part, who);
                Map<String, Object> small = new LinkedHashMap<>();
                small.put("id", d.get("id"));
                small.put("name", d.get("name"));
                small.put("byteSize", d.get("byteSize"));
                out.add(small);
            }
            outbox.publish(DomainEvents.DMS_SHARE_ACCESSED, Map.of("organizationId", org, "shareLinkId",
                    link.get("id"), "action", "upload", "count", parts.size()));
            return out;
        });
    }

    // ---- crypto ----

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2$120000$" + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(pbkdf2(password, salt,
                120_000));
    }

    static boolean checkPassword(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        byte[] salt = HexFormat.of().parseHex(parts[2]);
        byte[] expected = HexFormat.of().parseHex(parts[3]);
        return MessageDigest.isEqual(expected, pbkdf2(password, salt, Integer.parseInt(parts[1])));
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
