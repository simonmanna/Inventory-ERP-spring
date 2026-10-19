package com.inventoryerp.kernel.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Token helpers (port of {@code refresh-token.util.ts}): sha256-at-rest, opaque refresh tokens. */
public final class AuthenticationHashes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthenticationHashes() {}

    /** A freshly minted refresh token: raw value (returned once), its hash and expiry (7 days). */
    public record RefreshTokenValue(String value, String hash, Instant expiresAt) {}

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hashRefreshToken(String token) {
        return sha256(token);
    }

    /** 48 random bytes, base64url. */
    public static RefreshTokenValue newRefreshTokenValue() {
        String value = randomBase64Url(48);
        return new RefreshTokenValue(value, hashRefreshToken(value), Instant.now().plus(Duration.ofDays(7)));
    }

    public static String randomBase64Url(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
