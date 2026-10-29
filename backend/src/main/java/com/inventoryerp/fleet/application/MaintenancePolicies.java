package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State machines and rules of fleet maintenance (1.6): the work-order lifecycle, the defect lifecycle, the inspection
 * result and the dispatch-block rule (an open <b>critical</b> defect blocks the unit).
 */
public final class MaintenancePolicies {

    private MaintenancePolicies() {}

    // ------------------------------------------------------------------ work orders

    public static final String DRAFT = "draft";
    public static final String OPEN = "open";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String CLOSED = "closed";
    public static final String CANCELLED = "cancelled";

    /** Statuses holding a unit's live work (one live work order per schedule). */
    public static final Set<String> LIVE = Set.of(DRAFT, OPEN, IN_PROGRESS);

    private static final Map<String, Set<String>> WO_NEXT = Map.of(
            DRAFT, Set.of(OPEN, CANCELLED),
            OPEN, Set.of(IN_PROGRESS, CANCELLED),
            IN_PROGRESS, Set.of(COMPLETED, CANCELLED),
            COMPLETED, Set.of(CLOSED),
            CLOSED, Set.of(),
            CANCELLED, Set.of());

    /** {@code draft → open → in_progress → completed → closed}; {@code draft / open / in_progress → cancelled}. */
    public static void assertWorkOrderTransition(String code, String from, String to) {
        if (!WO_NEXT.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.badRequest("Work order " + code + " is " + label(from) + "; it cannot be "
                    + verb(to));
        }
    }

    public static boolean canTransition(String from, String to) {
        return WO_NEXT.getOrDefault(from, Set.of()).contains(to);
    }

    /** Spare parts are issued while the work order is open or in progress. */
    public static boolean acceptsParts(String status) {
        return OPEN.equals(status) || IN_PROGRESS.equals(status);
    }

    /** Labour, bills, part returns and reversals: also after completion (late bills), never once closed. */
    public static boolean acceptsCosts(String status) {
        return OPEN.equals(status) || IN_PROGRESS.equals(status) || COMPLETED.equals(status);
    }

    /** Header, tasks: editable until the work is completed. */
    public static boolean editable(String status) {
        return LIVE.contains(status);
    }

    /** Preventive work is planned downtime; everything else is a failure (unplanned). */
    public static boolean unplanned(String workOrderType) {
        return !"preventive".equals(workOrderType);
    }

    // ------------------------------------------------------------------ defects

    public static final List<String> SEVERITIES = List.of("minor", "major", "critical");
    public static final Set<String> DEFECT_OPEN = Set.of("open", "in_repair");

    /** The dispatch-block rule: a critical defect blocks the unit while it is open or being repaired. */
    public static boolean blocksDispatch(String severity, String status) {
        return "critical".equals(severity) && DEFECT_OPEN.contains(status);
    }

    /** Work-order priority for a defect's severity. */
    public static String priorityFor(String severity) {
        return switch (severity) {
            case "critical" -> "urgent";
            case "major" -> "high";
            default -> "normal";
        };
    }

    /** {@code open ⇄ in_repair → resolved}; {@code open / in_repair → dismissed}. */
    public static void assertDefectTransition(String code, String from, String to) {
        boolean ok = switch (to) {
            case "in_repair" -> "open".equals(from);
            case "open" -> "in_repair".equals(from);
            case "resolved", "dismissed" -> DEFECT_OPEN.contains(from);
            default -> false;
        };
        if (!ok) {
            throw ApiException.badRequest("Defect " + code + " is " + label(from) + "; it cannot be " + label(to));
        }
    }

    // ------------------------------------------------------------------ inspections

    /** {@code fail} when any item failed, else {@code pass} ({@code na} items do not count). */
    public static String inspectionResult(Collection<String> itemResults) {
        return itemResults.contains("fail") ? "fail" : "pass";
    }

    static String label(String status) {
        return status == null ? "" : status.replace('_', ' ');
    }

    private static String verb(String to) {
        return switch (to) {
            case OPEN -> "opened";
            case IN_PROGRESS -> "started";
            case COMPLETED -> "completed";
            case CLOSED -> "closed";
            case CANCELLED -> "cancelled";
            default -> label(to);
        };
    }
}
