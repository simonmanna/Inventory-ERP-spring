package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Fuel-card numbers (fleet 1.5): the full number is only seen on input. It is normalised (spaces / dashes removed,
 * upper case), checked (4–32 letters or digits), and kept as a masked form ({@code **** 1234}), the last four
 * characters and a keyed SHA-256 hash (organization + number) used for uniqueness. Responses, audit rows and logs only
 * ever carry the masked form.
 */
public final class FuelCardNumbers {

    private FuelCardNumbers() {}

    /** Normalised card number or a 400 when it is not 4–32 letters / digits. */
    public static String normalize(String raw) {
        String n = raw == null ? "" : raw.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
        if (!n.matches("[A-Z0-9]{4,32}")) {
            throw ApiException.badRequest("A fuel card number has 4 to 32 letters or digits");
        }
        return n;
    }

    public static String last4(String normalized) {
        return normalized.substring(normalized.length() - 4);
    }

    /** {@code **** 1234}. */
    public static String mask(String normalized) {
        return "**** " + last4(normalized);
    }

    /** Hex SHA-256 of {@code organizationId:number}. */
    public static String hash(String organizationId, String normalized) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((organizationId + ":" + normalized)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
