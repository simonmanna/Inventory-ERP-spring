package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import java.util.Set;

/**
 * Trip (load) lifecycle (fleet 1.3): {@code planned → loading → weighed → dispatched → in_transit → delivered →
 * closed}, with {@code cancelled} before the goods leave and {@code returned} once they did. A trip may skip loading /
 * weighing (dispatched at the planned quantity) and the in-transit step (POD straight after dispatch); a weighed trip
 * can be re-weighed. {@code closed}, {@code cancelled} and {@code returned} are terminal.
 */
public final class TripStatusPolicy {

    private TripStatusPolicy() {}

    static final Map<String, Set<String>> NEXT = Map.of(
            "planned", Set.of("loading", "weighed", "dispatched", "cancelled"),
            "loading", Set.of("weighed", "dispatched", "cancelled"),
            "weighed", Set.of("weighed", "dispatched", "cancelled"),
            "dispatched", Set.of("in_transit", "delivered", "returned"),
            "in_transit", Set.of("delivered", "returned"),
            "delivered", Set.of("closed"),
            "closed", Set.of(),
            "cancelled", Set.of(),
            "returned", Set.of());

    static final Map<String, String> LABEL = Map.of(
            "planned", "planned", "loading", "loading", "weighed", "weighed", "dispatched", "dispatched",
            "in_transit", "in transit", "delivered", "delivered", "closed", "closed", "cancelled", "cancelled",
            "returned", "returned");

    static final Map<String, String> ACTION = Map.of(
            "loading", "loaded", "weighed", "weighed", "dispatched", "dispatched", "in_transit", "set in transit",
            "delivered", "delivered", "closed", "closed", "cancelled", "cancelled", "returned", "returned");

    public static boolean canMove(String from, String to) {
        return NEXT.getOrDefault(from, Set.of()).contains(to);
    }

    /** 400 {@code "Trip TRP-000001 is delivered and cannot be cancelled"}. */
    public static void assertMove(String code, String from, String to) {
        if (!canMove(from, to)) {
            throw ApiException.badRequest("Trip " + code + " is " + label(from) + " and cannot be "
                    + ACTION.getOrDefault(to, to));
        }
    }

    /** Before the goods leave: planned / loading / weighed (assignment and quantities may change). */
    public static boolean isOpen(String status) {
        return "planned".equals(status) || "loading".equals(status) || "weighed".equals(status);
    }

    /** Goods on the road: dispatched / in transit. */
    public static boolean isOnRoad(String status) {
        return "dispatched".equals(status) || "in_transit".equals(status);
    }

    /** Holds quantity of its delivery order (not cancelled, not returned). */
    public static boolean isActive(String status) {
        return !"cancelled".equals(status) && !"returned".equals(status);
    }

    public static String label(String status) {
        return LABEL.getOrDefault(status, status);
    }
}
