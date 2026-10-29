package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Set;

/**
 * Vehicle status machine (ADR-S009 §4): {@code active, in_workshop, breakdown, idle} move freely between each other;
 * {@code disposed} is terminal. A breakdown or disposal needs a reason. Only an {@code active} vehicle can be
 * dispatched, assigned or checked out by later sub-modules ({@link #isOperational}).
 */
public final class VehicleStatusPolicy {

    public static final String ACTIVE = "active";
    public static final String IN_WORKSHOP = "in_workshop";
    public static final String BREAKDOWN = "breakdown";
    public static final String IDLE = "idle";
    public static final String DISPOSED = "disposed";

    public static final List<String> STATUSES = List.of(ACTIVE, IN_WORKSHOP, BREAKDOWN, IDLE, DISPOSED);

    private static final Set<String> NEEDS_REASON = Set.of(BREAKDOWN, DISPOSED);

    private VehicleStatusPolicy() {}

    /** Validates {@code from → to} for vehicle {@code code}; throws a 400 with the reason when it is not allowed. */
    public static void assertTransition(String code, String from, String to, String reason) {
        if (!STATUSES.contains(to)) {
            throw ApiException.badRequest("Unknown vehicle status '" + to + "'");
        }
        if (DISPOSED.equals(from)) {
            throw ApiException.badRequest("Vehicle " + code + " is disposed; its status can no longer change");
        }
        if (to.equals(from)) {
            throw ApiException.badRequest("Vehicle " + code + " is already " + label(to));
        }
        if (NEEDS_REASON.contains(to) && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("A reason is required to mark a vehicle as " + label(to));
        }
    }

    /** Available for work (dispatch, assignment, check-out). */
    public static boolean isOperational(String status) {
        return ACTIVE.equals(status);
    }

    /** Human label ({@code in_workshop} → "in workshop"). */
    public static String label(String status) {
        return status == null ? "" : status.replace('_', ' ');
    }
}
