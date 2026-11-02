package com.inventoryerp.kernel.files;

import com.inventoryerp.kernel.domain.File;
import com.inventoryerp.kernel.domain.FileVisibility;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * File storage + signed downloads (port of {@code files.service.ts}). Keys are
 * {@code <orgId>/<yyyy-mm-dd>/<32 hex><ext>}; download URLs are HMAC-SHA256(JWT_ACCESS_SECRET) over
 * {@code id:expires:organizationId}, valid one hour.
 *
 * <p>DM-0 hardening (ADR-S044, deviation from Nest): a signed URL is only issued for a file of the caller's
 * organization and the token is bound to the file's organization; executable and active-content uploads are refused.
 */
@Service
public class FilesService {

    private static final long SIGN_TTL_MS = 60 * 60 * 1000;

    /** Extensions never accepted (executables, scripts, active web content). */
    static final java.util.Set<String> BLOCKED_EXTENSIONS = java.util.Set.of(".exe", ".dll", ".bat", ".cmd", ".com",
            ".msi", ".scr", ".ps1", ".vbs", ".vbe", ".js", ".mjs", ".jar", ".sh", ".php", ".html", ".htm", ".xhtml",
            ".hta", ".jsp", ".asp", ".aspx", ".cpl", ".lnk", ".reg");

    /** Content types a browser may render inline from a signed URL (no script execution). */
    public static final java.util.Set<String> INLINE_TYPES = java.util.Set.of("application/pdf", "image/png",
            "image/jpeg", "image/gif", "image/webp", "text/plain", "text/csv", "audio/mpeg", "video/mp4");

    /** File metadata. */
    public record FileMeta(String id, String organizationId, String filename, String contentType, long byteSize,
            String storageKey) {}

    /** A resolved download. */
    public record Download(String filename, String contentType, InputStream stream) {}

    private final EntityManager em;
    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final byte[] signingSecret;
    private final long maxBytes;
    private final SecureRandom random = new SecureRandom();

    public FilesService(EntityManager em, JdbcClient jdbc, FileStorage storage,
            @Value("${app.jwt.access-secret:dev-signing-secret}") String secret,
            @Value("${UPLOAD_MAX_BYTES:26214400}") String maxBytes) {
        this.em = em;
        this.jdbc = jdbc;
        this.storage = storage;
        this.signingSecret = secret.getBytes(StandardCharsets.UTF_8);
        this.maxBytes = parseBytes(maxBytes);
    }

    @Transactional
    public Map<String, Object> upload(String filename, String contentType, byte[] bytes, String ownerType, String ownerId) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("Empty file");
        }
        if (bytes.length > maxBytes) {
            throw ApiException.badRequest("File exceeds maximum size of " + maxBytes + " bytes");
        }
        String orgId = TenantContext.organizationId();
        String name = filename == null ? "" : filename;
        int dot = name.lastIndexOf('.');
        String ext = dot <= 0 ? "" : name.substring(dot).toLowerCase().replaceAll("[^.a-z0-9]", "");
        if (BLOCKED_EXTENSIONS.contains(ext) || (contentType != null && isActiveContent(contentType))) {
            throw ApiException.badRequest("File type not allowed");
        }
        byte[] rnd = new byte[16];
        random.nextBytes(rnd);
        String key = orgId + "/" + JsonConfig.toJsIso(Instant.now()).substring(0, 10) + "/" + HexFormat.of().formatHex(rnd) + ext;
        String checksum = sha256Hex(bytes);
        storage.put(key, bytes, contentType);
        File file = new File();
        try {
            file.setOrganizationId(orgId);
            file.setUploadedById(TenantContext.userId());
            file.setFilename(sanitizeFilename(name));
            file.setContentType(contentType);
            file.setByteSize(bytes.length);
            file.setStorageKey(key);
            file.setVisibility(FileVisibility.PRIVATE);
            file.setOwnerType(ownerType);
            file.setOwnerId(ownerId);
            file.setChecksum(checksum);
            em.persist(file);
            em.flush();
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Unable to save file record: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", file.getId());
        out.put("storageKey", file.getStorageKey());
        return out;
    }

    /** {@code {url, expiresAt}}; only for a file of the caller's organization (when there is a tenant). */
    public Map<String, Object> signDownload(String fileId) {
        String fileOrg = jdbc.sql("SELECT \"organizationId\" FROM \"File\" WHERE id = ?").param(fileId)
                .query(String.class).optional().orElse("");
        String currentOrg = TenantContext.optionalOrganizationId();
        if (currentOrg != null && !fileOrg.isEmpty() && !fileOrg.equals(currentOrg)) {
            throw ApiException.notFound("File not found");
        }
        long exp = System.currentTimeMillis() + SIGN_TTL_MS;
        String sig = hmac(fileId + ":" + exp + ":" + fileOrg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", "/api/v1/files/" + fileId + "/download?token=" + sig + "&expires=" + exp);
        out.put("expiresAt", JsonConfig.toJsIso(Instant.ofEpochMilli(exp)));
        return out;
    }

    /** Verify the signed URL; the token is the authorization (public route). */
    public Download resolveSignedDownload(String fileId, String token, String expires) {
        double exp = com.inventoryerp.kernel.common.JsNumbers.number(expires);
        if (Double.isNaN(exp) || Double.isInfinite(exp) || System.currentTimeMillis() > exp) {
            throw ApiException.badRequest("Signed URL has expired");
        }
        record Row(String filename, String contentType, String storageKey, String visibility, String organizationId) {}
        Row found = jdbc.sql("""
                        SELECT filename, "contentType", "storageKey", visibility::text AS visibility, "organizationId"
                        FROM "File" WHERE id = ? AND "deletedAt" IS NULL LIMIT 1""")
                .param(fileId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .optional()
                .orElse(null);
        String expected = hmac(fileId + ":" + jsNumberString(exp) + ":" + (found == null ? "" : found.organizationId()));
        if (token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.badRequest("Invalid signed URL token");
        }
        if (found == null) {
            throw ApiException.notFound("File not found");
        }
        Row file = found;
        String currentOrg = TenantContext.optionalOrganizationId();
        if ("private".equals(file.visibility()) && currentOrg != null && !file.organizationId().equals(currentOrg)) {
            throw ApiException.notFound("File not found");
        }
        return new Download(file.filename(), file.contentType(), storage.get(file.storageKey()));
    }

    @Transactional
    public void remove(String fileId) {
        String orgId = TenantContext.organizationId();
        List<File> rows = em.createQuery("select f from File f where f.id = :id and f.organizationId = :org", File.class)
                .setParameter("id", fileId)
                .setParameter("org", orgId)
                .getResultList();
        if (rows.isEmpty()) {
            throw ApiException.notFound("File not found");
        }
        File file = rows.getFirst();
        if ("local".equals(storage.driver())) {
            storage.delete(file.getStorageKey());
        }
        file.setDeletedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForOwner(String ownerType, String ownerId) {
        StringBuilder q = new StringBuilder("select f from File f where f.organizationId = :org and f.deletedAt is null");
        if (ownerType != null) {
            q.append(" and f.ownerType = :ot");
        }
        if (ownerId != null) {
            q.append(" and f.ownerId = :oid");
        }
        var query = em.createQuery(q + " order by f.createdAt desc", File.class)
                .setParameter("org", TenantContext.organizationId());
        if (ownerType != null) {
            query.setParameter("ot", ownerType);
        }
        if (ownerId != null) {
            query.setParameter("oid", ownerId);
        }
        return EntityJson.list(query.getResultList());
    }

    /** Metadata of a live file of the current organization, or {@code null}. */
    public FileMeta meta(String fileId) {
        return jdbc.sql("""
                        SELECT id, "organizationId", filename, "contentType", "byteSize", "storageKey" FROM "File"
                        WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                .params(fileId, TenantContext.organizationId())
                .query((rs, n) -> new FileMeta(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getString(6)))
                .optional().orElse(null);
    }

    /** Metadata of a live file in any organization (internal jobs, public share links). */
    public FileMeta metaUnscoped(String fileId) {
        return jdbc.sql("""
                        SELECT id, "organizationId", filename, "contentType", "byteSize", "storageKey" FROM "File"
                        WHERE id = ? AND "deletedAt" IS NULL""")
                .param(fileId)
                .query((rs, n) -> new FileMeta(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getString(6)))
                .optional().orElse(null);
    }

    /** Open the bytes of a file resolved by {@link #meta} / {@link #metaUnscoped}. */
    public InputStream open(FileMeta file) {
        return storage.get(file.storageKey());
    }

    /** A download of a resolved file. */
    public Download download(FileMeta file) {
        return new Download(file.filename(), file.contentType(), storage.get(file.storageKey()));
    }

    /** Maximum upload size in bytes ({@code UPLOAD_MAX_BYTES}). */
    public long maxBytes() {
        return maxBytes;
    }

    static boolean isActiveContent(String contentType) {
        String t = contentType.toLowerCase();
        return t.startsWith("text/html") || t.startsWith("application/xhtml") || t.contains("javascript")
                || t.startsWith("application/x-msdownload") || t.startsWith("application/x-sh");
    }

    static String sanitizeFilename(String name) {
        String s = name.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private String hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsNumberString(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static long parseBytes(String v) {
        String s = v.trim().toUpperCase();
        if (s.endsWith("MB")) {
            return Long.parseLong(s.substring(0, s.length() - 2).trim()) * 1024 * 1024;
        }
        if (s.endsWith("KB")) {
            return Long.parseLong(s.substring(0, s.length() - 2).trim()) * 1024;
        }
        return Long.parseLong(s);
    }
}
