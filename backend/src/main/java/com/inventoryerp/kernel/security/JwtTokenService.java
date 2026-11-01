package com.inventoryerp.kernel.security;

import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HS256 access / refresh / POS tokens, wire-compatible with the Nest {@code jsonwebtoken} tokens
 * (port of {@code jwt-token.service.ts}). Access tokens carry {@code {sub, organizationId, email,
 * type: "access"}} and deliberately no permissions (docs/CONTRACT.md section 7); permissions are always
 * read from the database. Only {@code HS256} is accepted on verify.
 */
@Service
public class JwtTokenService {

    /** Decoded claims of a verified token. */
    public record TokenClaims(String sub, String organizationId, String email, String type, long iat, long exp) {}

    private static final String HEADER = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern DURATION = Pattern.compile(
            "^(-?\\d*\\.?\\d+) *(milliseconds?|msecs?|ms|seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h|days?|d|weeks?|w|years?|yrs?|y)?$",
            Pattern.CASE_INSENSITIVE);

    private final byte[] accessSecret;
    private final byte[] refreshSecret;
    private final byte[] posSecret;
    private final long accessTtlSec;
    private final long refreshTtlSec;
    private final long posTtlSec;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtTokenService(JwtProperties props) {
        this(props, Clock.systemUTC());
    }

    JwtTokenService(JwtProperties props, Clock clock) {
        if (props.accessSecret() == null || props.accessSecret().isEmpty()) {
            throw new IllegalStateException("JWT_ACCESS_SECRET env var is required");
        }
        if (props.refreshSecret() == null || props.refreshSecret().isEmpty()) {
            throw new IllegalStateException("JWT_REFRESH_SECRET env var is required");
        }
        this.accessSecret = props.accessSecret().getBytes(StandardCharsets.UTF_8);
        this.refreshSecret = props.refreshSecret().getBytes(StandardCharsets.UTF_8);
        this.posSecret = props.effectivePosSecret().getBytes(StandardCharsets.UTF_8);
        this.accessTtlSec = parseTtlSeconds(props.accessTtl());
        this.refreshTtlSec = parseTtlSeconds(props.refreshTtl());
        this.posTtlSec = parseTtlSeconds(props.posTtl());
        this.clock = clock;
    }

    public String signAccess(String sub, String organizationId, String email) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", sub);
        claims.put("organizationId", organizationId);
        claims.put("email", email);
        claims.put("type", "access");
        return sign(claims, accessSecret, accessTtlSec);
    }

    public String signRefresh(String sub, String organizationId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", sub);
        claims.put("organizationId", organizationId);
        claims.put("type", "refresh");
        return sign(claims, refreshSecret, refreshTtlSec);
    }

    /** POS cashier token (JWT_POS_SECRET when set, else the access secret). */
    public String signPos(String sub, String organizationId, String email) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", sub);
        claims.put("organizationId", organizationId);
        claims.put("email", email);
        claims.put("type", "pos");
        return sign(claims, posSecret, posTtlSec);
    }

    /** OAuth client-credentials token (ADR-S029 §5): access secret, {@code type: client}, granted scopes. */
    public String signClient(String clientRowId, String organizationId, java.util.List<String> scopes, long ttlSec) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", clientRowId);
        claims.put("organizationId", organizationId);
        claims.put("type", "client");
        claims.put("scope", String.join(" ", scopes));
        return sign(claims, accessSecret, ttlSec);
    }

    /** Claims of a client token: row id, organization and scopes. */
    public record ClientClaims(String clientRowId, String organizationId, java.util.List<String> scopes) {}

    /** Verify a client token; throws {@link IllegalArgumentException} when invalid, expired or not a client token. */
    public ClientClaims verifyClient(String token) {
        TokenClaims c = verify(token, accessSecret);
        if (!"client".equals(c.type())) {
            throw new IllegalArgumentException("wrong token type");
        }
        JsonNode p = MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.", -1)[1]));
        String scope = p.path("scope").asString("");
        return new ClientClaims(c.sub(), c.organizationId(),
                scope.isBlank() ? java.util.List.of() : java.util.List.of(scope.trim().split("\\s+")));
    }

    /**
     * Verify a back-office access token. A missing {@code type} is accepted (tokens minted before the
     * claim existed); a mismatched one never is, so a POS or refresh token cannot stand in for it.
     */
    public TokenClaims verifyAccess(String token) {
        try {
            TokenClaims c = verify(token, accessSecret);
            if (c.type() != null && !"access".equals(c.type())) {
                throw new IllegalArgumentException("wrong token type");
            }
            return c;
        } catch (RuntimeException e) {
            throw ApiException.unauthorized("Invalid or expired access token");
        }
    }

    public TokenClaims verifyRefresh(String token) {
        try {
            TokenClaims c = verify(token, refreshSecret);
            if (!"refresh".equals(c.type())) {
                throw new IllegalArgumentException("wrong token type");
            }
            return c;
        } catch (RuntimeException e) {
            throw ApiException.unauthorized("Invalid or expired refresh token");
        }
    }

    /** Verify a POS cashier token; throws {@link IllegalArgumentException} when invalid/expired/wrong type. */
    public TokenClaims verifyPos(String token) {
        TokenClaims c = verify(token, posSecret);
        if (!"pos".equals(c.type())) {
            throw new IllegalArgumentException("wrong token type");
        }
        return c;
    }

    private String sign(Map<String, Object> claims, byte[] secret, long ttlSec) {
        long iat = clock.millis() / 1000;
        claims.put("iat", iat);
        claims.put("exp", iat + ttlSec);
        String payload = b64(MAPPER.writeValueAsBytes(claims));
        String signingInput = HEADER + "." + payload;
        return signingInput + "." + b64(hmac(secret, signingInput));
    }

    private TokenClaims verify(String token, byte[] secret) {
        if (token == null) {
            throw new IllegalArgumentException("jwt must be provided");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("jwt malformed");
        }
        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        if (!"HS256".equals(header.path("alg").asString(""))) {
            throw new IllegalArgumentException("invalid algorithm");
        }
        byte[] expected = hmac(secret, parts[0] + "." + parts[1]);
        byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("invalid signature");
        }
        JsonNode p = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        long now = clock.millis() / 1000;
        if (p.hasNonNull("exp") && now >= p.get("exp").asLong()) {
            throw new IllegalArgumentException("jwt expired");
        }
        if (p.hasNonNull("nbf") && now < p.get("nbf").asLong()) {
            throw new IllegalArgumentException("jwt not active");
        }
        return new TokenClaims(
                text(p, "sub"), text(p, "organizationId"), text(p, "email"), text(p, "type"),
                p.path("iat").asLong(0), p.path("exp").asLong(0));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static byte[] hmac(byte[] secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** {@code jsonwebtoken expiresIn} semantics: a number is seconds, a string uses the {@code ms} format. */
    static long parseTtlSeconds(String value) {
        String v = value.trim();
        if (v.matches("\\d+")) {
            // vercel/ms: a bare numeric *string* is milliseconds.
            return Long.parseLong(v) / 1000;
        }
        Matcher m = DURATION.matcher(v);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid token TTL: " + value);
        }
        double n = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "ms" : m.group(2).toLowerCase(Locale.ROOT);
        Duration base = switch (unit.charAt(0)) {
            case 'y' -> Duration.ofMillis(31_557_600_000L);
            case 'w' -> Duration.ofDays(7);
            case 'd' -> Duration.ofDays(1);
            case 'h' -> Duration.ofHours(1);
            case 's' -> Duration.ofSeconds(1);
            case 'm' -> unit.startsWith("ms") || unit.startsWith("msec") || unit.startsWith("milli")
                    ? Duration.ofMillis(1) : Duration.ofMinutes(1);
            default -> Duration.ofMillis(1);
        };
        return (long) Math.floor(base.toMillis() * n / 1000);
    }
}
