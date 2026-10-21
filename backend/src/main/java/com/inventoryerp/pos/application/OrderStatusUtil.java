package com.inventoryerp.pos.application;

import java.util.List;
import java.util.Map;

/** Order-status wire compatibility (port of {@code order-status.util.ts}). */
public final class OrderStatusUtil {

    public static final List<String> CANONICAL = List.of("draft", "confirmed", "in_progress", "completed", "closed",
            "cancelled");
    public static final List<String> LEGACY = List.of("open", "preparing", "ready", "served");
    public static final List<String> ACCEPTED = List.of("draft", "confirmed", "in_progress", "completed", "closed",
            "cancelled", "open", "preparing", "ready", "served");

    private static final Map<String, String> LEGACY_TO_CANONICAL = Map.of("open", "confirmed", "preparing",
            "in_progress", "ready", "in_progress", "served", "completed");
    private static final Map<String, String> CANONICAL_TO_LEGACY = Map.of("confirmed", "open", "in_progress",
            "preparing", "completed", "served");

    private OrderStatusUtil() {}

    public static String toCanonical(String status) {
        if (status == null || status.isEmpty()) {
            return status;
        }
        return LEGACY_TO_CANONICAL.getOrDefault(status, status);
    }

    public static String toLegacy(String status) {
        if (status == null || status.isEmpty()) {
            return status;
        }
        return CANONICAL_TO_LEGACY.getOrDefault(status, status);
    }

    /** {@code {...o, ...withLegacyOrderStatus(o.status)}}: sets {@code status} then {@code legacyStatus}. */
    public static Map<String, Object> withLegacy(Map<String, Object> o) {
        Object s = o.get("status");
        o.put("status", s);
        o.put("legacyStatus", s == null ? null : toLegacy(s.toString()));
        return o;
    }
}
