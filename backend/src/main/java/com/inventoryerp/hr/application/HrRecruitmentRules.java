package com.inventoryerp.hr.application;

import java.util.List;
import java.util.Map;

/** The state machines of recruitment (HR-1): which status or stage may follow which. */
public final class HrRecruitmentRules {

    private HrRecruitmentRules() {}

    static final List<String> OPENING_STATUSES = List.of("draft", "open", "on_hold", "closed", "cancelled");
    static final List<String> STAGES = List.of("applied", "screening", "interview", "offer", "hired", "rejected",
            "withdrawn");

    private static final Map<String, List<String>> OPENING_NEXT = Map.of(
            "draft", List.of("open", "cancelled"),
            "open", List.of("on_hold", "closed", "cancelled"),
            "on_hold", List.of("open", "closed", "cancelled"),
            "closed", List.of("open"),
            "cancelled", List.of());

    /** {@code hired} is not here: only the hire step reaches it, because it also creates the employee. */
    private static final Map<String, List<String>> STAGE_NEXT = Map.of(
            "applied", List.of("screening", "interview", "rejected", "withdrawn"),
            "screening", List.of("interview", "offer", "rejected", "withdrawn"),
            "interview", List.of("offer", "rejected", "withdrawn"),
            "offer", List.of("interview", "rejected", "withdrawn"),
            "hired", List.of(),
            "rejected", List.of(),
            "withdrawn", List.of());

    public static boolean openingMayBecome(String from, String to) {
        return OPENING_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean stageMayBecome(String from, String to) {
        return STAGE_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    /** A hired, rejected or withdrawn applicant takes no further action. */
    public static boolean stageIsFinal(String stage) {
        return STAGE_NEXT.getOrDefault(stage, List.of()).isEmpty();
    }
}
