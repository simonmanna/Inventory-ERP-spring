package com.inventoryerp.license.crypto;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The customer-visible licence key: {@code ERP-XXXX-XXXX-XXXX-CCCC}.
 *
 * <p>Crockford base32 (no I, L, O or U, so nothing reads as 1/0) over 60 random bits, plus a final
 * group holding a CRC-16/CCITT check. A mistyped key therefore fails locally, before any request
 * reaches the server, and support can read a key down a phone line without ambiguity.
 *
 * <p>Normalisation accepts lower case, missing dashes, and the usual visual confusions (O→0, I/L→1)
 * so a customer who retypes a key from an invoice is not punished for it.
 */
public final class LicenseKeyCodec {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford
    private static final String PREFIX = "ERP";
    private static final int GROUPS = 3; // random groups, 4 chars each = 60 bits
    private static final int GROUP_LENGTH = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LicenseKeyCodec() {}

    public static String generate() {
        StringBuilder body = new StringBuilder(GROUPS * GROUP_LENGTH);
        for (int i = 0; i < GROUPS * GROUP_LENGTH; i++) {
            body.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        String payload = body.toString();
        return format(payload + checkGroup(payload));
    }

    /** {@code true} when the key is well formed and its check group matches. */
    public static boolean isValid(String key) {
        String compact = compact(key);
        if (compact == null || compact.length() != (GROUPS + 1) * GROUP_LENGTH) {
            return false;
        }
        for (int i = 0; i < compact.length(); i++) {
            if (ALPHABET.indexOf(compact.charAt(i)) < 0) {
                return false;
            }
        }
        String payload = compact.substring(0, GROUPS * GROUP_LENGTH);
        return compact.substring(GROUPS * GROUP_LENGTH).equals(checkGroup(payload));
    }

    /** Canonical, storable form, or {@code null} when the input cannot be a licence key. */
    public static String normalize(String key) {
        String compact = compact(key);
        if (compact == null || compact.length() != (GROUPS + 1) * GROUP_LENGTH) {
            return null;
        }
        return format(compact);
    }

    /** For logs and support tickets: {@code ERP-…-4C1W}. */
    public static String mask(String key) {
        String normalized = normalize(key);
        if (normalized == null) {
            return "ERP-…";
        }
        return PREFIX + "-…-" + normalized.substring(normalized.length() - GROUP_LENGTH);
    }

    private static String compact(String key) {
        if (key == null) {
            return null;
        }
        String upper = key.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        if (upper.startsWith(PREFIX)) {
            upper = upper.substring(PREFIX.length());
        }
        if (upper.isEmpty()) {
            return null;
        }
        // Visual confusions that are not in the alphabet map onto what the eye saw.
        return upper.replace('O', '0').replace('I', '1').replace('L', '1').replace('U', 'V');
    }

    private static String format(String compact) {
        StringBuilder out = new StringBuilder(PREFIX);
        for (int i = 0; i < compact.length(); i += GROUP_LENGTH) {
            out.append('-').append(compact, i, Math.min(i + GROUP_LENGTH, compact.length()));
        }
        return out.toString();
    }

    /** CRC-16/CCITT-FALSE of the payload, as four base32 characters carrying one nibble each. */
    private static String checkGroup(String payload) {
        int crc = 0xFFFF;
        for (char c : payload.toCharArray()) {
            crc ^= (c & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        StringBuilder out = new StringBuilder(GROUP_LENGTH);
        for (int i = GROUP_LENGTH - 1; i >= 0; i--) {
            out.append(ALPHABET.charAt((crc >>> (i * 4)) & 0x0F));
        }
        return out.toString();
    }
}
