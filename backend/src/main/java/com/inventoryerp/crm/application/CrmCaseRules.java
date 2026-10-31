package com.inventoryerp.crm.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Case rules (CRM-1): the status machine and the response time of each priority. */
public final class CrmCaseRules {

    private CrmCaseRules() {}

    static final List<String> TYPES = List.of("complaint", "service_request");
    static final List<String> CATEGORIES = List.of("delivery", "quality", "billing", "service", "other");
    static final List<String> PRIORITIES = List.of("low", "normal", "high", "urgent");
    static final List<String> STATUSES = List.of("open", "in_progress", "resolved", "closed", "cancelled");
    static final List<String> LIVE = List.of("open", "in_progress");

    private static final Map<String, List<String>> NEXT = Map.of(
            "open", List.of("in_progress", "resolved", "cancelled"),
            "in_progress", List.of("open", "resolved", "cancelled"),
            "resolved", List.of("in_progress", "closed"),
            "closed", List.of(),
            "cancelled", List.of());

    private static final Map<String, Duration> RESPONSE = Map.of(
            "urgent", Duration.ofHours(4),
            "high", Duration.ofHours(24),
            "normal", Duration.ofHours(72),
            "low", Duration.ofDays(7));

    public static boolean mayBecome(String from, String to) {
        return NEXT.getOrDefault(from, List.of()).contains(to);
    }

    /** When a case raised at {@code from} with this priority is due. */
    public static Instant dueAt(Instant from, String priority) {
        return from.plus(RESPONSE.getOrDefault(priority, RESPONSE.get("normal")));
    }
}
