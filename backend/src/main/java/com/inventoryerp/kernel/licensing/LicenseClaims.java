package com.inventoryerp.kernel.licensing;

import com.inventoryerp.shared.web.EntityJson;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The verified contents of a licence token, in a shape the rest of the install can use.
 *
 * <p>Parsing is deliberately forgiving about <em>absent</em> members and strict about
 * <em>malformed</em> ones. A newer licence server may add claims this release has never heard of,
 * and an install must keep working when it does; but a date that will not parse means the token is
 * not what it claims to be, and that is a verification failure rather than something to shrug at.
 *
 * @see LicenseStateMachine for what is then done with it
 */
public record LicenseClaims(
        String issuer,
        String licenseKey,
        String fingerprint,
        String activationId,
        Instant tokenExpiry,
        String customer,
        String customerId,
        String plan,
        String planName,
        String period,
        int periodCount,
        String status,
        boolean trial,
        Instant startsAt,
        /** {@code null} for a perpetual licence. */
        Instant expiresAt,
        Instant graceUntil,
        int graceDays,
        long heartbeatSeconds,
        Instant refreshAfter,
        Set<String> features,
        Integer maxDevices,
        Integer maxUsers,
        Integer maxOrganizations,
        boolean offline,
        Instant serverTime) {

    /** Default check-in interval when a token predates the {@code heartbeatSeconds} claim. */
    private static final long DEFAULT_HEARTBEAT_SECONDS = 6 * 3600L;

    public static LicenseClaims parse(String payloadJson) {
        JsonNode root = EntityJson.tree(payloadJson);
        JsonNode lic = root == null ? null : root.path("lic");
        if (lic == null || !lic.isObject()) {
            throw new IllegalArgumentException("Licence token carries no 'lic' claim");
        }
        JsonNode limits = lic.path("limits");

        return new LicenseClaims(
                string(root, "iss"),
                string(root, "sub"),
                string(root, "aud"),
                string(root, "jti"),
                epochSeconds(root, "exp"),
                string(lic, "customer"),
                string(lic, "customerId"),
                string(lic, "plan"),
                string(lic, "planName"),
                string(lic, "period"),
                integer(lic, "periodCount", 1),
                string(lic, "status"),
                lic.path("trial").asBoolean(),
                instant(lic, "startsAt"),
                instant(lic, "expiresAt"),
                instant(lic, "graceUntil"),
                integer(lic, "graceDays", 0),
                longValue(lic, "heartbeatSeconds", DEFAULT_HEARTBEAT_SECONDS),
                instant(lic, "refreshAfter"),
                strings(lic.path("features")),
                nullableInteger(limits, "devices"),
                nullableInteger(limits, "users"),
                nullableInteger(limits, "organizations"),
                lic.path("offline").asBoolean(),
                instant(lic, "serverTime"));
    }

    public boolean isPerpetual() {
        return expiresAt == null;
    }

    public boolean has(String feature) {
        return features.contains(feature);
    }

    private static String string(JsonNode parent, String name) {
        JsonNode node = parent.path(name);
        return node.isString() ? node.asString() : null;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        if (array.isArray()) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                if (item != null && item.isString() && !item.asString().isBlank()) {
                    out.add(item.asString());
                }
            }
        }
        return Set.copyOf(out);
    }

    private static int integer(JsonNode parent, String name, int fallback) {
        JsonNode node = parent.path(name);
        return node.isNumber() ? node.intValue() : fallback;
    }

    private static long longValue(JsonNode parent, String name, long fallback) {
        JsonNode node = parent.path(name);
        return node.isNumber() ? node.longValue() : fallback;
    }

    private static Integer nullableInteger(JsonNode parent, String name) {
        JsonNode node = parent.path(name);
        return node.isNumber() ? node.intValue() : null;
    }

    private static Instant epochSeconds(JsonNode parent, String name) {
        JsonNode node = parent.path(name);
        return node.isNumber() ? Instant.ofEpochSecond(node.longValue()) : null;
    }

    /** An absent date is {@code null}; a present but unparseable one means the token is not ours. */
    private static Instant instant(JsonNode parent, String name) {
        JsonNode node = parent.path(name);
        if (!node.isString()) {
            return null;
        }
        String text = node.asString();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Licence token has an unreadable date: " + text, e);
        }
    }
}
