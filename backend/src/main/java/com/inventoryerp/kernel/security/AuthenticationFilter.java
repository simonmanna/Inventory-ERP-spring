package com.inventoryerp.kernel.security;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.web.JsonResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves the request identity and tenant (port of the tenant middleware in Nest {@code main.ts}).
 * Invalid or absent tokens fall through unauthenticated; the access guard enforces.
 *
 * <ul>
 *   <li>{@code Authorization: Bearer} access token; on the three SSE paths only, {@code ?access_token=}.
 *   <li>{@code X-Pos-User} (or {@code ?pos_token=} on SSE paths): a valid POS token of the same
 *       organization makes the cashier the acting user. A revoked cashier gets 401 with
 *       {@code X-Pos-Session: revoked}, except on the PIN screens.
 *   <li>{@code X-Device-Token} (when no valid bearer): an enrolled offline device establishes the
 *       organization only (identity travels per sync op).
 * </ul>
 */
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    /** Routes allowed to authenticate from a query-string token (EventSource cannot send headers). */
    public static final List<String> EVENT_STREAM_PATHS =
            List.of("/pos/tables/stream", "/pos/kds/stream", "/communication/stream");

    /** Request attribute holding the enrolled device {@code {id, organizationId, branchId}}. */
    public static final String DEVICE_ATTRIBUTE = "erp.posDevice";

    private static final Pattern PIN_SCREEN = Pattern.compile("/pos/auth/(pin-login|staff)$");

    private final JwtTokenService jwt;
    private final PosSessionCheck posSessionCheck;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public AuthenticationFilter(JwtTokenService jwt, PosSessionCheck posSessionCheck, JdbcClient jdbc,
            JsonMapper mapper) {
        this.jwt = jwt;
        this.posSessionCheck = posSessionCheck;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    static boolean isEventStreamPath(String path) {
        return EVENT_STREAM_PATHS.stream().anyMatch(path::endsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (resolve(request, response)) {
                chain.doFilter(request, response);
            }
        } finally {
            TenantContext.clear();
            MDC.remove("organizationId");
            MDC.remove("userId");
        }
    }

    /** Returns false when the response was already written (revoked POS session). */
    private boolean resolve(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        boolean eventStream = isEventStreamPath(path);
        String token = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring("Bearer ".length());
        } else if (eventStream && request.getParameter("access_token") != null) {
            token = request.getParameter("access_token");
        }
        if (token != null) {
            JwtTokenService.TokenClaims payload = null;
            try {
                payload = jwt.verifyAccess(token);
            } catch (RuntimeException invalid) {
                // invalid token: continue unauthenticated, guards will reject if needed
            }
            if (payload != null) {
                AuthUser bearer = new AuthUser(payload.sub(), payload.organizationId(), payload.email());
                AuthUser effective = bearer;
                String posUserId = null;
                String posHeader = request.getHeader("X-Pos-User");
                if (posHeader == null && eventStream) {
                    posHeader = request.getParameter("pos_token");
                }
                if (posHeader != null) {
                    try {
                        JwtTokenService.TokenClaims pos = jwt.verifyPos(posHeader);
                        if (payload.organizationId() != null && payload.organizationId().equals(pos.organizationId())) {
                            effective = new AuthUser(pos.sub(), payload.organizationId(), pos.email());
                            posUserId = pos.sub();
                        }
                    } catch (RuntimeException ignored) {
                        // invalid/expired POS token: fall back to the JWT identity
                    }
                }
                if (posUserId == null) {
                    proceed(request, effective, null);
                    return true;
                }
                boolean allowed;
                try {
                    allowed = posSessionCheck.posUserStillAllowed(payload.organizationId(), posUserId);
                } catch (RuntimeException e) {
                    // Never let the check itself lock the tills: a failed lookup trusts the signed token.
                    log.warn("POS session check failed: {}", e.toString());
                    allowed = true;
                }
                if (allowed) {
                    proceed(request, effective, posUserId);
                    return true;
                }
                if (PIN_SCREEN.matcher(path).find()) {
                    proceed(request, bearer, null);
                    return true;
                }
                response.setHeader(PosSessionCheck.POS_SESSION_HEADER, "revoked");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("statusCode", 401);
                body.put("message", "This cashier can no longer use the POS. Sign in with another PIN.");
                JsonResponses.write(mapper, response, 401, body);
                return false;
            }
        }
        String deviceToken = request.getHeader("X-Device-Token");
        if (deviceToken != null) {
            try {
                String hash = sha256Hex(deviceToken);
                var device = jdbc.sql("""
                                SELECT id, "organizationId", "branchId" FROM "PosDevice"
                                WHERE "tokenHash" = ? AND "revokedAt" IS NULL LIMIT 1""")
                        .param(hash)
                        .query((rs, n) -> {
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("id", rs.getString("id"));
                            d.put("organizationId", rs.getString("organizationId"));
                            d.put("branchId", rs.getString("branchId"));
                            return d;
                        })
                        .optional();
                if (device.isPresent()) {
                    Map<String, Object> d = device.get();
                    request.setAttribute(DEVICE_ATTRIBUTE, d);
                    TenantContext.set(new TenantStore((String) d.get("organizationId"), null, null, null,
                            (String) d.get("id"), (String) d.get("branchId")));
                }
            } catch (RuntimeException e) {
                log.warn("Device token lookup failed: {}", e.toString());
            }
        }
        return true;
    }

    private static void proceed(HttpServletRequest request, AuthUser identity, String posUserId) {
        request.setAttribute(AuthUser.ATTRIBUTE, identity);
        TenantContext.set(new TenantStore(identity.organizationId(), identity.sub(), identity.email(), posUserId,
                null, null));
        MDC.put("organizationId", String.valueOf(identity.organizationId()));
        MDC.put("userId", String.valueOf(identity.sub()));
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
