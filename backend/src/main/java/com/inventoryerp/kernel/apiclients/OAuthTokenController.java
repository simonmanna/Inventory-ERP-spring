package com.inventoryerp.kernel.apiclients;

import com.inventoryerp.kernel.apiclients.ApiClientService.OAuthError;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.idempotency.CachedBodyFilter;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.Throttle;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /oauth/token}: OAuth 2.0 client-credentials grant (RFC 6749 §4.4; ADR-S029 §5). Form-encoded (the RFC)
 * or JSON; client authentication in the body or with HTTP Basic. Errors use the RFC 6749 §5.2 shape, not the API
 * error envelope, because OAuth client libraries parse them.
 */
@RestController
@RequestMapping("/oauth")
public class OAuthTokenController {

    private final ApiClientService clients;

    public OAuthTokenController(ApiClientService clients) {
        this.clients = clients;
    }

    @Public
    @Throttle(limit = 30, ttlMs = 60_000)
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> tokenForm(@RequestBody(required = false) String body,
            HttpServletRequest req) {
        Map<String, String> form = new java.util.LinkedHashMap<>();
        // CachedBodyFilter consumed the stream; Spring rebuilds a form body from (empty) parameters, so read the copy.
        if ((body == null || body.isBlank()) && req.getAttribute(CachedBodyFilter.RAW_BODY) instanceof byte[] raw) {
            body = new String(raw, StandardCharsets.UTF_8);
        }
        if (body != null) {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    form.putIfAbsent(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        // A servlet filter may already have read the body into the request parameters.
        req.getParameterMap().forEach((k, v) -> {
            if (v.length > 0) {
                form.putIfAbsent(k, v[0]);
            }
        });
        return issue(form.get("grant_type"), form.get("client_id"), form.get("client_secret"), form.get("scope"),
                req);
    }

    @Public
    @Throttle(limit = 30, ttlMs = 60_000)
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> tokenJson(@RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return issue(str(body.get("grant_type")), str(body.get("client_id")), str(body.get("client_secret")),
                str(body.get("scope")), req);
    }

    private ResponseEntity<Map<String, Object>> issue(String grantType, String clientId, String secret, String scope,
            HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon > 0) {
                    clientId = java.net.URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
                    secret = java.net.URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException e) {
                return error(new OAuthError(401, "invalid_client", "Client authentication failed"));
            }
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                    .body(clients.token(grantType, clientId, secret, scope));
        } catch (OAuthError e) {
            return error(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(OAuthError e) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore());
        if (e.status() == 401) {
            b.header("WWW-Authenticate", "Basic realm=\"oauth\"");
        }
        return b.body(Obj.of("error", e.code(), "error_description", e.getMessage()));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
