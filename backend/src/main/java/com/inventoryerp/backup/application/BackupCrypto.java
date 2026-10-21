package com.inventoryerp.backup.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backup encryption in the OpenSSL {@code enc} container format (port of {@code backup-crypto.ts}), so an encrypted
 * dump opens on any machine with stock tooling:
 *
 * <pre>openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -in backup.dump.enc -out backup.dump</pre>
 *
 * Layout: {@code "Salted__"} | 8-byte salt | AES-256-CBC ciphertext (PKCS#7). Key and IV come from
 * PBKDF2-HMAC-SHA256(password, salt, 600000) → 48 bytes. Backups written before that format used 10,000 iterations
 * and still decrypt.
 */
public final class BackupCrypto {

    public static final String ENCRYPTED_SUFFIX = ".enc";
    public static final int PBKDF2_ITERATIONS = 600_000;
    static final int LEGACY_ITERATIONS = 10_000;
    private static final byte[] MAGIC = "Salted__".getBytes(StandardCharsets.ISO_8859_1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {}

    static byte[][] deriveKeyIv(String password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 48 * 8);
        byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return new byte[][] {Arrays.copyOfRange(out, 0, 32), Arrays.copyOfRange(out, 32, 48)};
    }

    public static void encryptFile(Path src, Path dest, String password) throws IOException {
        if (password == null || password.isEmpty()) {
            throw new IOException("encryption password is empty");
        }
        byte[] salt = new byte[8];
        RANDOM.nextBytes(salt);
        try {
            byte[][] keyIv = deriveKeyIv(password, salt, PBKDF2_ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyIv[0], "AES"), new IvParameterSpec(keyIv[1]));
            try (OutputStream raw = Files.newOutputStream(dest); InputStream in = Files.newInputStream(src)) {
                raw.write(MAGIC);
                raw.write(salt);
                try (CipherOutputStream out = new CipherOutputStream(raw, cipher)) {
                    in.transferTo(out);
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public static void decryptFile(Path src, Path dest, String password) throws IOException {
        if (password == null || password.isEmpty()) {
            throw new IOException("backup is encrypted but no password is configured (BACKUP_ENCRYPTION_PASSWORD)");
        }
        byte[] header = new byte[16];
        try (InputStream in = Files.newInputStream(src)) {
            if (in.readNBytes(header, 0, 16) < 16 || !Arrays.equals(Arrays.copyOfRange(header, 0, 8), MAGIC)) {
                throw new IOException("not an encrypted backup (missing Salted__ header)");
            }
        }
        byte[] salt = Arrays.copyOfRange(header, 8, 16);
        Exception last = null;
        for (int iterations : new int[] {PBKDF2_ITERATIONS, LEGACY_ITERATIONS}) {
            try {
                byte[][] keyIv = deriveKeyIv(password, salt, iterations);
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyIv[0], "AES"), new IvParameterSpec(keyIv[1]));
                try (InputStream in = Files.newInputStream(src); OutputStream out = Files.newOutputStream(dest)) {
                    in.skipNBytes(16);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        byte[] chunk = cipher.update(buf, 0, n);
                        if (chunk != null) {
                            out.write(chunk);
                        }
                    }
                    out.write(cipher.doFinal());
                }
                return;
            } catch (BadPaddingException e) {
                last = e;
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        if (last instanceof BadPaddingException) {
            throw new IOException("decryption failed: wrong password or corrupted file");
        }
        throw new IOException(last == null ? "decryption failed" : last.getMessage(), last);
    }
}
