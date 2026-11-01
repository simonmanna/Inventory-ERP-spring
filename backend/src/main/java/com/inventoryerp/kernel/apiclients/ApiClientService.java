package com.inventoryerp.kernel.apiclients;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.JwtTokenService;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API clients for the OAuth 2.0 client-credentials grant (ADR-S029 §5): management (secret shown once, stored as
 * SHA-256), and token issuance for {@code POST /oauth/token}.
 */
@Service
public class ApiClientService {

    public static final long TOKEN_TTL_SECONDS = 3600;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String COLUMNS = "id, name, \"clientId\", \"secretHint\", scopes, \"isActive\", \"expiresAt\","
            + " \"lastUsedAt\", \"revokedAt\", \"createdBy\", \"createdAt\", \"updatedAt\"";

    /** An OAuth error: RFC 6749 §5.2 code and HTTP status. */
    public static final class OAuthError extends RuntimeException {
        private final String code;
        private final int status;

        public OAuthError(int status, String code, String description) {
            super(description);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    private final JdbcClient jdbc;
    private final JwtTokenService jwt;
    private final PermissionService permissions;
    private final AuditService audit;

    public ApiClientService(JdbcClient jdbc, JwtTokenService jwt, PermissionService permissions, AuditService audit) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.permissions = permissions;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT " + COLUMNS + " FROM \"ApiClient\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"createdAt\" DESC").param(org()));
    }

    /** Every permission key a client may be given (the caller can grant only those it holds). */
    public Map<String, Object> scopes() {
        List<String> mine = permissions.current();
        return Obj.of("scopes", Permissions.ALL.stream().sorted().toList(), "grantable",
                Permissions.ALL.stream().filter(mine::contains).sorted().toList());
    }

    @Transactional
    public Map<String, Object> create(String name, List<String> scopes, Instant expiresAt) {
        List<String> clean = cleanScopes(scopes);
        String id = UUID.randomUUID().toString();
        String clientId = "ierp_" + HexFormat.of().formatHex(random(10));
        String secret = newSecret();
        jdbc.sql("INSERT INTO \"ApiClient\" (id, \"organizationId\", name, \"clientId\", \"secretHash\", \"secretHint\","
                + " scopes, \"expiresAt\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), required(name), clientId, sha256(secret), hint(secret), clean.toArray(String[]::new),
                        expiresAt == null ? null : Timestamp.from(expiresAt), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("ApiClient", id, "create", null, Obj.of("name", name, "scopes", clean)));
        Map<String, Object> out = get(id);
        out.put("clientSecret", secret);
        return out;
    }

    @Transactional
    public Map<String, Object> update(String id, String name, List<String> scopes, Boolean isActive, Instant expiresAt,
            boolean clearExpiry) {
        Map<String, Object> before = get(id);
        if (before.get("revokedAt") != null) {
            throw ApiException.badRequest("The API client is revoked");
        }
        List<String> clean = scopes == null ? null : cleanScopes(scopes);
        jdbc.sql("UPDATE \"ApiClient\" SET name = ?, scopes = ?, \"isActive\" = ?, \"expiresAt\" = ?, \"updatedAt\" ="
                + " now() WHERE id = ? AND \"organizationId\" = ?")
                .params(name == null ? before.get("name") : required(name),
                        clean == null ? currentScopes(id) : clean.toArray(String[]::new),
                        isActive == null ? before.get("isActive") : isActive,
                        clearExpiry ? null : expiresAt != null ? Timestamp.from(expiresAt) : expiry(id), id, org())
                .update();
        permissions.evictAll();
        Map<String, Object> after = get(id);
        audit.recordInTx(AuditInput.of("ApiClient", id, "update", before, after));
        return after;
    }

    /** New secret; the old one stops working at once. */
    @Transactional
    public Map<String, Object> rotate(String id) {
        Map<String, Object> before = get(id);
        if (before.get("revokedAt") != null) {
            throw ApiException.badRequest("The API client is revoked");
        }
        String secret = newSecret();
        jdbc.sql("UPDATE \"ApiClient\" SET \"secretHash\" = ?, \"secretHint\" = ?, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(sha256(secret), hint(secret), id, org()).update();
        audit.recordInTx(AuditInput.of("ApiClient", id, "update", null, Obj.of("secret", "rotated")));
        Map<String, Object> out = get(id);
        out.put("clientSecret", secret);
        return out;
    }

    /** Revoke for good: its tokens stop working within the permission cache window (evicted here). */
    @Transactional
    public Map<String, Object> revoke(String id) {
        get(id);
        jdbc.sql("UPDATE \"ApiClient\" SET \"revokedAt\" = coalesce(\"revokedAt\", now()), \"isActive\" = false,"
                + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update();
        permissions.evictAll();
        audit.recordInTx(AuditInput.of("ApiClient", id, "delete"));
        return get(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT " + COLUMNS + " FROM \"ApiClient\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("API client not found");
        }
        return r;
    }

    private String[] currentScopes(String id) {
        Object v = get(id).get("scopes");
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            l.forEach(s -> out.add(String.valueOf(s)));
        }
        return out.toArray(String[]::new);
    }

    private Timestamp expiry(String id) {
        return jdbc.sql("SELECT \"expiresAt\" FROM \"ApiClient\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Timestamp.class).optional().orElse(null);
    }

    private static List<String> cleanScopes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw ApiException.badRequest("scopes should not be empty");
        }
        List<String> mine = TenantContext.permissions();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : requested) {
            if (!Permissions.ALL.contains(s)) {
                throw ApiException.badRequest("Unknown scope '" + s + "'");
            }
            if (!mine.contains(s)) {
                throw ApiException.forbidden("You cannot grant a scope you do not hold: " + s);
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    // ---- token endpoint -------------------------------------------------------------------------------------------

    /** RFC 6749 §4.4 token response for {@code clientId} / {@code secret} and the optional space-separated scope. */
    public Map<String, Object> token(String grantType, String clientId, String secret, String scope) {
        if (grantType == null || grantType.isBlank()) {
            throw new OAuthError(400, "invalid_request", "grant_type is required");
        }
        if (!"client_credentials".equals(grantType)) {
            throw new OAuthError(400, "unsupported_grant_type", "Only client_credentials is supported");
        }
        if (clientId == null || clientId.isBlank() || secret == null || secret.isBlank()) {
            throw new OAuthError(401, "invalid_client", "Client authentication failed");
        }
        Optional<Map<String, Object>> found = Optional.ofNullable(SqlRows.one(jdbc.sql("SELECT id, \"organizationId\","
                + " \"secretHash\", scopes, \"isActive\", \"revokedAt\", \"expiresAt\" FROM \"ApiClient\" WHERE"
                + " \"clientId\" = ?").param(clientId)));
        String presented = sha256(secret);
        boolean ok = found.isPresent() && MessageDigest.isEqual(presented.getBytes(StandardCharsets.US_ASCII),
                String.valueOf(found.get().get("secretHash")).getBytes(StandardCharsets.US_ASCII));
        Map<String, Object> c = found.orElse(null);
        if (!ok || !Boolean.TRUE.equals(c.get("isActive")) || c.get("revokedAt") != null
                || (c.get("expiresAt") instanceof Instant exp && !exp.isAfter(Instant.now()))) {
            throw new OAuthError(401, "invalid_client", "Client authentication failed");
        }
        List<String> held = new ArrayList<>();
        if (c.get("scopes") instanceof List<?> l) {
            l.forEach(s -> held.add(String.valueOf(s)));
        }
        List<String> granted = held;
        if (scope != null && !scope.isBlank()) {
            granted = List.of(scope.trim().split("\\s+"));
            for (String s : granted) {
                if (!held.contains(s)) {
                    throw new OAuthError(400, "invalid_scope", "The client does not hold scope " + s);
                }
            }
        }
        jdbc.sql("UPDATE \"ApiClient\" SET \"lastUsedAt\" = now() WHERE id = ?").param(c.get("id")).update();
        String token = jwt.signClient((String) c.get("id"), (String) c.get("organizationId"), granted,
                TOKEN_TTL_SECONDS);
        return Obj.of("access_token", token, "token_type", "Bearer", "expires_in", TOKEN_TTL_SECONDS, "scope",
                String.join(" ", granted));
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static String newSecret() {
        return "ierps_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String hint(String secret) {
        return "…" + secret.substring(secret.length() - 4);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String required(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        return name.trim();
    }
}
