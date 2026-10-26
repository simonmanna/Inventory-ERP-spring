package com.inventoryerp.kernel.licensing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Verifies a licence token: the reading half of the licence server's {@code Jws} and
 * {@code Ed25519Keys}, with no signing counterpart on purpose — an installation holds public keys
 * only and so cannot mint a licence for itself.
 *
 * <p>EdDSA is in the JDK (Java 15+), so verification adds no dependency to a customer's deployment.
 *
 * <p><b>This must stay byte-compatible with the server's {@code Jws}.</b> The format is the contract
 * between the two builds: header {@code {"alg":"EdDSA","typ":"JWT","kid":...}}, signing input
 * {@code base64url(header) + "." + base64url(payload)} in ASCII. {@code LicenseTokenFormatTest} on
 * this side pins a vector produced by that one.
 */
public final class LicenseJws {

    /** A parsed but not yet verified token. */
    public record Parsed(String kid, String algorithm, String payloadJson, String signingInput,
            byte[] signature) {}

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo; the remaining 32 bytes are the raw key. */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private LicenseJws() {}

    /** Split a compact token. Throws {@link IllegalArgumentException} if it is not one. */
    public static Parsed parse(String compact) {
        if (compact == null || compact.isBlank()) {
            throw new IllegalArgumentException("Empty token");
        }
        String[] parts = compact.trim().split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("A compact JWS has three parts, got " + parts.length);
        }
        String headerJson;
        String payloadJson;
        byte[] signature;
        try {
            headerJson = new String(B64D.decode(parts[0]), StandardCharsets.UTF_8);
            payloadJson = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
            signature = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Token is not valid base64url", e);
        }
        return new Parsed(stringMember(headerJson, "kid"), stringMember(headerJson, "alg"), payloadJson,
                parts[0] + "." + parts[1], signature);
    }

    public static boolean verify(PublicKey key, Parsed token) {
        if (key == null || !"EdDSA".equals(token.algorithm())) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(token.signingInput().getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(token.signature());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** Rebuild a public key from the 32 raw bytes of an RFC 8037 OKP JWK. */
    public static PublicKey fromRawPublic(byte[] raw) {
        if (raw == null || raw.length != 32) {
            throw new IllegalArgumentException("An Ed25519 public key is 32 bytes");
        }
        byte[] spki = new byte[SPKI_PREFIX.length + 32];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, 32);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("This JVM has no Ed25519 provider", e);
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("Not a valid Ed25519 public key", e);
        }
    }

    /** Decode the base64url {@code x} member of an OKP JWK. */
    public static PublicKey fromJwkX(String base64UrlX) {
        return fromRawPublic(B64D.decode(base64UrlX));
    }

    /**
     * Reads one top-level string member out of the JWS header. Hand-rolled so this class needs no
     * JSON library and stays trivially portable between the two builds; the header is a fixed
     * three-member object the server writes itself.
     */
    private static String stringMember(String json, String name) {
        String needle = "\"" + name + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                out.append(json.charAt(++i));
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return null;
    }
}
