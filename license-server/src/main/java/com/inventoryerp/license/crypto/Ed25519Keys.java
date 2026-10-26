package com.inventoryerp.license.crypto;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Ed25519 keys on the JDK's own provider (EdDSA is built in since Java 15) — no third-party crypto
 * library, so {@code licence verification on a customer install adds no dependency}.
 *
 * <p>Three encodings are in play:
 *
 * <ul>
 *   <li><b>SPKI / PKCS#8 base64</b> — how keys are stored in {@code signing_key}.
 *   <li><b>raw 32 bytes</b> — the RFC 8032 public key, base64url-encoded as the {@code x} member of
 *       an RFC 8037 OKP JWK. An Ed25519 SubjectPublicKeyInfo is always the fixed 12-byte prefix
 *       below followed by exactly those 32 bytes, so the conversion is a slice in each direction.
 * </ul>
 */
public final class Ed25519Keys {

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo: SEQUENCE, AlgorithmIdentifier 1.3.101.112, BIT STRING. */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    public static final int RAW_LENGTH = 32;

    private Ed25519Keys() {}

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("This JVM has no Ed25519 provider", e);
        }
    }

    public static String encodePublic(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String encodePrivate(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublic(String base64Spki) {
        try {
            return keyFactory().generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Spki)));
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("Not a valid Ed25519 public key", e);
        }
    }

    public static PrivateKey decodePrivate(String base64Pkcs8) {
        try {
            return keyFactory().generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Pkcs8)));
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("Not a valid Ed25519 private key", e);
        }
    }

    /** The 32 raw public-key bytes, for the {@code x} member of an OKP JWK. */
    public static byte[] rawPublic(PublicKey key) {
        byte[] spki = key.getEncoded();
        if (spki.length != SPKI_PREFIX.length + RAW_LENGTH) {
            throw new IllegalArgumentException("Unexpected Ed25519 SPKI length: " + spki.length);
        }
        return Arrays.copyOfRange(spki, SPKI_PREFIX.length, spki.length);
    }

    /** Rebuild a public key from the 32 raw bytes of an OKP JWK. */
    public static PublicKey fromRawPublic(byte[] raw) {
        if (raw.length != RAW_LENGTH) {
            throw new IllegalArgumentException("An Ed25519 public key is 32 bytes, got " + raw.length);
        }
        byte[] spki = new byte[SPKI_PREFIX.length + RAW_LENGTH];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, RAW_LENGTH);
        try {
            return keyFactory().generatePublic(new X509EncodedKeySpec(spki));
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("Not a valid Ed25519 public key", e);
        }
    }

    /**
     * Stable key id: the first 16 bytes of SHA-256 over the raw public key, hex. Deriving it from the
     * key itself means the same key always carries the same {@code kid}, however it was imported.
     */
    public static String kid(PublicKey key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(rawPublic(key));
            return HexFormat.of().formatHex(Arrays.copyOf(digest, 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static KeyFactory keyFactory() {
        try {
            return KeyFactory.getInstance("Ed25519");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("This JVM has no Ed25519 provider", e);
        }
    }
}
