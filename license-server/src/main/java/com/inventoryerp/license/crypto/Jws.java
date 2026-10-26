package com.inventoryerp.license.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Minimal RFC 7515 compact JWS with RFC 8037 {@code EdDSA} (Ed25519).
 *
 * <p><b>This class is duplicated, verbatim in behaviour, as
 * {@code com.inventoryerp.kernel.licensing.LicenseJws} in the ERP backend (verify half only).</b>
 * The two builds are independent, so the format is the contract between them: header
 * {@code {"alg":"EdDSA","typ":"JWT","kid":...}}, signing input
 * {@code base64url(header) + "." + base64url(payload)}, ASCII. Any change here must land there in
 * the same commit, with {@code LicenseTokenFormatTest} on both sides pinning a known-good vector.
 */
public final class Jws {

    /** A parsed but not yet verified token. */
    public record Parsed(String kid, String algorithm, String payloadJson, String signingInput, byte[] signature) {}

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Jws() {}

    public static String sign(PrivateKey key, String kid, String payloadJson) {
        String header = "{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"" + requirePlainKid(kid) + "\"}";
        String signingInput = encode(header) + "." + encode(payloadJson);
        return signingInput + "." + B64.encodeToString(signBytes(key, signingInput));
    }

    /** Split a compact token without verifying it; throws when it is not three base64url parts. */
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
        return new Parsed(
                stringMember(headerJson, "kid"),
                stringMember(headerJson, "alg"),
                payloadJson,
                parts[0] + "." + parts[1],
                signature);
    }

    public static boolean verify(PublicKey key, Parsed token) {
        if (!"EdDSA".equals(token.algorithm())) {
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

    private static byte[] signBytes(PrivateKey key, String signingInput) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign with this key", e);
        }
    }

    private static String encode(String json) {
        return B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The header is assembled by string concatenation, so the key id may not contain anything that
     * would need escaping. {@link Ed25519Keys#kid} produces hex, which always satisfies this.
     */
    private static String requirePlainKid(String kid) {
        if (kid == null || !kid.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Key id must be 1-64 chars of [A-Za-z0-9._-]");
        }
        return kid;
    }

    /**
     * Reads one top-level string member out of the JWS header. The header is a three-member object
     * this class writes itself; a hand-rolled read keeps the codec free of a JSON dependency so it
     * stays trivially portable between the two builds.
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
