package com.inventoryerp.lossprevention.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts evidence clips before they reach the file storage (ADR-S077 §8): AES-256-GCM with a key derived by scrypt
 * from {@code LP_EVIDENCE_KEY}, else {@code ENCRYPTION_KEY}, else the JWT access secret. A stored clip is
 * {@code "LPE1" + 12-byte IV + ciphertext + 16-byte tag}; the evidence id is authenticated with it, so a file moved to
 * another record does not decrypt.
 *
 * <p>Changing the key makes clips stored under the old one unreadable; {@link #keyId()} is stored with each clip so the
 * screen can say so instead of reporting tampering.
 */
@Component
public class LpEvidenceCipher {

    public static final String FORMAT = "aes-256-gcm/LPE1";
    private static final byte[] MAGIC = "LPE1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SALT = "inventory-erp-lp-evidence-v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** Why a stored clip could not be opened. */
    public static final class IntegrityException extends Exception {
        public IntegrityException(String message) {
            super(message);
        }
    }

    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public LpEvidenceCipher(@Value("${LP_EVIDENCE_KEY:}") String evidenceKey,
            @Value("${ENCRYPTION_KEY:}") String encryptionKey,
            @Value("${app.jwt.access-secret:}") String accessSecret) {
        String secret = !evidenceKey.isBlank() ? evidenceKey : !encryptionKey.isBlank() ? encryptionKey : accessSecret;
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Evidence encryption needs LP_EVIDENCE_KEY, ENCRYPTION_KEY or"
                    + " JWT_ACCESS_SECRET. Refusing to start.");
        }
        byte[] derived = SCrypt.generate(secret.getBytes(StandardCharsets.UTF_8), SALT, 16384, 8, 1, 32);
        this.key = new SecretKeySpec(derived, "AES");
        this.keyId = sha256(derived).substring(0, 16);
    }

    /** Identifies the key (not the key itself): stored with each clip. */
    public String keyId() {
        return keyId;
    }

    public byte[] encrypt(String evidenceId, byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(evidenceId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(MAGIC.length + IV_BYTES + sealed.length).put(MAGIC).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The clip, or an {@link IntegrityException} when the stored bytes are not what was stored. */
    public byte[] decrypt(String evidenceId, byte[] stored) throws IntegrityException {
        if (stored == null || stored.length < MAGIC.length + IV_BYTES + TAG_BITS / 8
                || !Arrays.equals(Arrays.copyOfRange(stored, 0, MAGIC.length), MAGIC)) {
            throw new IntegrityException("The stored clip is not in the evidence format");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, MAGIC.length, IV_BYTES));
            cipher.updateAAD(evidenceId.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(stored, MAGIC.length + IV_BYTES, stored.length - MAGIC.length - IV_BYTES);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IntegrityException("The stored clip failed its authentication check");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
