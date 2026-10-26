package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.AdminRole;
import com.inventoryerp.license.domain.AdminUser;
import com.inventoryerp.license.shared.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Admin session tokens — a different thing entirely from a licence.
 *
 * <p>Licences are asymmetric because thousands of installs must verify them without being able to
 * mint them. An admin token is only ever verified by the server that issued it, so a symmetric
 * HMAC-SHA256 JWS is the right tool and the signing key never has to leave this process.
 *
 * <p>When {@code app.admin.jwt-secret} is unset the service generates an ephemeral one and says so:
 * convenient in development (restarting logs admins out), refused outright in production by
 * {@link StartupChecks}.
 */
@Service
public class AdminTokens {

    private static final Logger log = LoggerFactory.getLogger(AdminTokens.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /** The identity carried by a verified access token. */
    public record Principal(String adminId, String email, AdminRole role) {}

    private final byte[] secret;
    private final Duration accessTtl;

    public AdminTokens(LicenseProperties properties) {
        String configured = properties.getAdmin().getJwtSecret();
        if (configured == null || configured.isBlank()) {
            byte[] ephemeral = new byte[32];
            RANDOM.nextBytes(ephemeral);
            this.secret = ephemeral;
            log.warn("app.admin.jwt-secret is not set: using an ephemeral signing secret. "
                    + "Admin sessions will not survive a restart. Set LICENSE_ADMIN_JWT_SECRET.");
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
        this.accessTtl = properties.getAdmin().getAccessTokenTtl();
    }

    public String accessToken(AdminUser admin, Instant now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", admin.getId());
        claims.put("email", admin.getEmail());
        claims.put("role", admin.getRole());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(accessTtl).getEpochSecond());
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String signingInput = encode(header) + "." + encode(Json.write(claims));
        return signingInput + "." + B64.encodeToString(hmac(signingInput));
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    /** Verified identity, or empty for anything that is not a live token of ours. */
    public Optional<Principal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(signingInput);
        byte[] presented;
        String payload;
        try {
            presented = B64D.decode(parts[2]);
            payload = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expected, presented)) {
            return Optional.empty();
        }
        Map<String, Object> claims = Json.readObject(payload);
        Object exp = claims.get("exp");
        if (!(exp instanceof Number expiry) || Instant.ofEpochSecond(expiry.longValue()).isBefore(Instant.now())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Principal(
                    String.valueOf(claims.get("sub")),
                    String.valueOf(claims.get("email")),
                    AdminRole.of(String.valueOf(claims.get("role")))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** An opaque refresh token; only its hash is ever stored. */
    public static String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    public static String hashRefreshToken(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private byte[] hmac(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign admin token", e);
        }
    }

    private static String encode(String json) {
        return B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
