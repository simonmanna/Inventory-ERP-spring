package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State machines of incidents, insurance claims, traffic fines and fine deductions (fleet 1.7).
 *
 * <ul>
 *   <li>Incident: {@code reported → investigating → repairing → closed}; any open state may jump forward or be
 *       {@code cancelled}; {@code closed → investigating} reopens (reason required); cancelled is terminal.</li>
 *   <li>Claim: {@code draft → submitted → approved | rejected}; {@code approved → settled}; draft / submitted /
 *       approved may be {@code withdrawn}; rejected, settled and withdrawn are final. Approved ≤ claimed,
 *       received ≤ approved.</li>
 *   <li>Fine: {@code open ⇄ disputed}, {@code open | disputed → cancelled}; {@code open → charged} (charge route),
 *       {@code charged → settled} (expense paid / deduction settled) and {@code charged → open} (charge reversed).</li>
 *   <li>Deduction: {@code pending → exported → settled}, {@code pending | exported → cancelled}.</li>
 * </ul>
 */
public final class IncidentPolicy {

    public static final List<String> INCIDENT_OPEN = List.of("reported", "investigating", "repairing");
    /** Claims that still await an outcome — they keep the incident from closing. */
    public static final List<String> CLAIM_PENDING = List.of("draft", "submitted", "approved");

    private static final Map<String, Set<String>> INCIDENT = Map.of(
            "reported", Set.of("investigating", "repairing", "closed", "cancelled"),
            "investigating", Set.of("repairing", "closed", "cancelled"),
            "repairing", Set.of("investigating", "closed", "cancelled"),
            "closed", Set.of("investigating"),
            "cancelled", Set.of());

    private static final Map<String, Set<String>> CLAIM = Map.of(
            "draft", Set.of("submitted", "withdrawn"),
            "submitted", Set.of("approved", "rejected", "withdrawn"),
            "approved", Set.of("settled", "withdrawn"),
            "rejected", Set.of(),
            "settled", Set.of(),
            "withdrawn", Set.of());

    /** Fine statuses a user may set directly (charging has its own route). */
    private static final Map<String, Set<String>> FINE_MANUAL = Map.of(
            "open", Set.of("disputed", "cancelled"),
            "disputed", Set.of("open", "cancelled"));

    private static final Map<String, Set<String>> DEDUCTION = Map.of(
            "pending", Set.of("exported", "settled", "cancelled"),
            "exported", Set.of("settled", "cancelled"),
            "settled", Set.of(),
            "cancelled", Set.of());

    private IncidentPolicy() {}

    public static boolean incidentCan(String from, String to) {
        return INCIDENT.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertIncident(String number, String from, String to, String reason) {
        if ("cancelled".equals(from)) {
            throw ApiException.badRequest("Incident " + number + " is cancelled");
        }
        if (!incidentCan(from, to)) {
            throw ApiException.badRequest("Incident " + number + " cannot move from " + label(from) + " to "
                    + label(to));
        }
        if (("cancelled".equals(to) || "closed".equals(from)) && blank(reason)) {
            throw ApiException.badRequest("A reason is required to " + ("closed".equals(from) ? "reopen" : "cancel")
                    + " an incident");
        }
    }

    public static boolean claimCan(String from, String to) {
        return CLAIM.getOrDefault(from, Set.of()).contains(to);
    }

    /** Status change of a claim with the amounts it needs at that step. */
    public static void assertClaim(String from, String to, BigDecimal claimed, BigDecimal approved,
            BigDecimal received, String reason) {
        if (!claimCan(from, to)) {
            throw ApiException.badRequest("A " + label(from) + " claim cannot be marked " + label(to));
        }
        if ("approved".equals(to) && approved == null) {
            throw ApiException.badRequest("The approved amount is required to approve a claim");
        }
        if (approved != null && claimed != null && approved.compareTo(claimed) > 0) {
            throw ApiException.badRequest("The approved amount cannot exceed the amount claimed");
        }
        if ("settled".equals(to) && (received == null || received.signum() <= 0)) {
            throw ApiException.badRequest("The amount received is required to settle a claim");
        }
        if (received != null && approved != null && received.compareTo(approved) > 0) {
            throw ApiException.badRequest("The amount received cannot exceed the approved amount");
        }
        if (("rejected".equals(to) || "withdrawn".equals(to)) && blank(reason)) {
            throw ApiException.badRequest("A reason is required to mark a claim " + to);
        }
    }

    public static boolean fineCan(String from, String to) {
        return FINE_MANUAL.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertFine(String number, String from, String to, String reason) {
        if (!fineCan(from, to)) {
            throw ApiException.badRequest("Fine " + number + " is " + label(from) + " and cannot be marked "
                    + label(to));
        }
        if (("cancelled".equals(to) || "disputed".equals(to)) && blank(reason)) {
            throw ApiException.badRequest("A reason is required to mark a fine " + to);
        }
    }

    public static boolean deductionCan(String from, String to) {
        return DEDUCTION.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertDeduction(String from, String to) {
        if (!deductionCan(from, to)) {
            throw ApiException.badRequest("A " + label(from) + " deduction cannot be marked " + label(to));
        }
    }

    static String label(String s) {
        return s == null ? "" : s.replace('_', ' ');
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
