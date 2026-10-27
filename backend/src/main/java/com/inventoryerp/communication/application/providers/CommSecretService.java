package com.inventoryerp.communication.application.providers;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM for communication secrets (port of {@code comm-secret.service.ts}), keyed off the dedicated
 * {@code COMM_ENCRYPTION_KEY} (base64, 32 bytes) rather than the kernel key. Output is {@code iv:tag:ciphertext}
 * (all base64), byte-compatible with Nest. The key is read lazily so the module loads without it.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class CommSecretService {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final String rawKey;
    private volatile SecretKeySpec cachedKey;

    public CommSecretService(@Value("${COMM_ENCRYPTION_KEY:}") String rawKey) {
        this.rawKey = rawKey;
    }

    /** True when {@code COMM_ENCRYPTION_KEY} is configured. */
    public boolean configured() {
        return rawKey != null && !rawKey.isBlank();
    }

    private SecretKeySpec key() {
        if (cachedKey != null) {
            return cachedKey;
        }
        if (!configured()) {
            throw new IllegalStateException("COMM_ENCRYPTION_KEY is not set — cannot encrypt communication secrets.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(rawKey.trim());
        } catch (IllegalArgumentException e) {
            key = new byte[0];
        }
        if (key.length != 32) {
            throw new IllegalStateException("COMM_ENCRYPTION_KEY must decode to 32 bytes (base64).");
        }
        cachedKey = new SecretKeySpec(key, "AES");
        return cachedKey;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] out = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            int ctLen = out.length - TAG_BITS / 8;
            byte[] ct = java.util.Arrays.copyOfRange(out, 0, ctLen);
            byte[] tag = java.util.Arrays.copyOfRange(out, ctLen, out.length);
            Base64.Encoder b64 = Base64.getEncoder();
            return b64.encodeToString(iv) + ":" + b64.encodeToString(tag) + ":" + b64.encodeToString(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Communication secret encryption failed.", e);
        }
    }

    public String decrypt(String payload) {
        String[] parts = payload == null ? new String[0] : payload.split(":", -1);
        if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalStateException("Malformed communication ciphertext.");
        }
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] iv = b64.decode(parts[0]);
            byte[] tag = b64.decode(parts[1]);
            byte[] ct = b64.decode(parts[2]);
            byte[] in = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, in, 0, ct.length);
            System.arraycopy(tag, 0, in, ct.length, tag.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(in), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Communication secret decryption failed.", e);
        }
    }
}
