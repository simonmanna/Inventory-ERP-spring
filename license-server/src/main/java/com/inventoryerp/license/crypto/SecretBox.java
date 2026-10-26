package com.inventoryerp.license.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Passphrase-wrapped secrets at rest: AES-256-GCM with a PBKDF2-HMAC-SHA256 derived key.
 *
 * <p>Wire format of the stored string is {@code v1.<b64 salt>.<b64 iv>.<b64 ciphertext+tag>}, so the
 * parameters travel with the value and a future {@code v2} can change them without a migration.
 * Used for signing private keys and admin TOTP secrets; the passphrase itself never touches the
 * database and in production comes from {@code LICENSE_SIGNING_PASSPHRASE}.
 */
public final class SecretBox {

    private static final String PREFIX = "v1";
    private static final int ITERATIONS = 600_000; // matches the ERP's backup crypto cost
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final char[] passphrase;

    public SecretBox(String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalArgumentException("A signing passphrase is required");
        }
        this.passphrase = passphrase.toCharArray();
    }

    public String seal(String plaintext) {
        byte[] salt = random(SALT_BYTES);
        byte[] iv = random(IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, derive(salt), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return String.join(".", PREFIX, B64.encodeToString(salt), B64.encodeToString(iv),
                    B64.encodeToString(sealed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot seal secret", e);
        }
    }

    public String open(String sealed) {
        String[] parts = sealed == null ? new String[0] : sealed.split("\\.");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("Unrecognised sealed-secret format");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derive(B64D.decode(parts[1])),
                    new GCMParameterSpec(TAG_BITS, B64D.decode(parts[2])));
            return new String(cipher.doFinal(B64D.decode(parts[3])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong passphrase or tampered ciphertext — never distinguish the two to a caller.
            throw new IllegalStateException("Cannot open sealed secret: wrong passphrase or corrupt value", e);
        }
    }

    private SecretKeySpec derive(byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(key, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive key", e);
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** Constant-time comparison for hashed tokens. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
