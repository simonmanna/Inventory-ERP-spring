package com.inventoryerp.kernel.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM field-level encryption, byte-compatible with the Nest {@code EncryptionService}:
 * key = scrypt(JWT_ACCESS_SECRET or ENCRYPTION_KEY, fixed salt, N=16384, r=8, p=1, 32 bytes), 12-byte IV,
 * ciphertext = base64("v1" + ct), iv/tag base64.
 *
 * <p>Deviation (bug fix, see docs/spec/kernel.md): Nest compared a 3-byte prefix to the 2-byte version
 * tag, so its {@code decrypt} could never succeed; this port checks the 2-byte prefix it writes.
 */
@Service
public class EncryptionService {

    /** Encrypted payload (all base64). */
    public record Encrypted(String ciphertext, String iv, String tag) {}

    private static final byte[] SALT = "erp-platform-v1-encryption-salt-do-not-change".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VERSION = "v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(@Value("${app.jwt.access-secret:}") String accessSecret,
            @Value("${ENCRYPTION_KEY:}") String encryptionKey) {
        String secret = accessSecret != null && !accessSecret.isEmpty() ? accessSecret : encryptionKey;
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException(
                    "EncryptionService requires JWT_ACCESS_SECRET or ENCRYPTION_KEY to derive its key. Refusing to start.");
        }
        byte[] derived = SCrypt.generate(secret.getBytes(StandardCharsets.UTF_8), SALT, 16384, 8, 1, 32);
        this.key = new SecretKeySpec(derived, "AES");
    }

    /** {@code null} for null/empty input. */
    public Encrypted encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] out = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] ct = Arrays.copyOfRange(out, 0, out.length - TAG_BITS / 8);
            byte[] tag = Arrays.copyOfRange(out, out.length - TAG_BITS / 8, out.length);
            byte[] versioned = ByteBuffer.allocate(VERSION.length + ct.length).put(VERSION).put(ct).array();
            Base64.Encoder b64 = Base64.getEncoder();
            return new Encrypted(b64.encodeToString(versioned), b64.encodeToString(iv), b64.encodeToString(tag));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inverse of {@link #encrypt}; throws on tamper or unknown version. */
    public String decrypt(Encrypted payload) {
        if (payload == null) {
            return null;
        }
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] iv = b64.decode(payload.iv());
        byte[] tag = b64.decode(payload.tag());
        byte[] raw = b64.decode(payload.ciphertext());
        if (raw.length < VERSION.length || !Arrays.equals(Arrays.copyOfRange(raw, 0, VERSION.length), VERSION)) {
            throw new IllegalArgumentException("Encrypted payload has unknown version; cannot decrypt");
        }
        try {
            byte[] ct = Arrays.copyOfRange(raw, VERSION.length, raw.length);
            byte[] combined = ByteBuffer.allocate(ct.length + tag.length).put(ct).put(tag).array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Unsupported state or unable to authenticate data", e);
        }
    }
}
