package com.inventoryerp.documents.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DMS constants and pure lifecycle decision logic (port of {@code dms.types.ts} + {@code dms.lifecycle.ts}). No I/O,
 * so the engine's rules are unit-testable without a database.
 */
public final class DmsRules {

    private DmsRules() {}

    /** Document-owned editable content fields — the only keys an {@code amend} payload may carry. */
    public static final List<String> AMENDABLE_FIELDS = List.of("terms", "notes", "clauses", "conditions");

    public static final List<String> TEMPLATE_FORMATS = List.of("a4", "thermal", "html", "plain", "email");
    public static final List<String> TEMPLATE_ENGINES = List.of("puppeteer", "escpos", "handlebars", "plain");
    public static final List<String> GUARD_VOCABULARY = List.of("permission", "requiresApproval", "requiresReason",
            "requiresPaid", "requiresSnapshot", "allowsAnyState");

    /** DRAFT prefix used by recurring / one-shot writers for placeholder numbers. */
    public static final String DRAFT_NUMBER_PREFIX = "DRAFT-";

    /** Reversal counterparts (§3.6). */
    public static final Map<String, String> REVERSAL_COUNTERPARTS = Map.of("sales_invoice", "credit_note",
            "pos_receipt", "credit_note", "school_fee_invoice", "credit_note", "rental_deposit", "credit_note",
            "vendor_bill", "debit_note");

    /** Phase 6 reissue counterparts (same table as reversals in Nest). */
    public static final Map<String, String> REISSUE_COUNTERPARTS = REVERSAL_COUNTERPARTS;

    /** Engine action → fixed audit vocabulary. */
    public static final Map<String, String> AUDIT_ACTION = Map.ofEntries(Map.entry("submit", "update"),
            Map.entry("approve", "approve"), Map.entry("reject", "reject"), Map.entry("confirm", "update"),
            Map.entry("issue", "issue"), Map.entry("activate", "update"), Map.entry("expire", "update"),
            Map.entry("revoke", "update"), Map.entry("post", "post"), Map.entry("pay", "update"),
            Map.entry("close", "update"), Map.entry("cancel", "cancel"), Map.entry("reverse", "cancel"),
            Map.entry("reissue", "post"), Map.entry("terminate", "update"), Map.entry("archive", "update"),
            Map.entry("amend", "update"));

    /** Engine action → declared domain event. */
    public static final Map<String, String> DOMAIN_EVENT = Map.ofEntries(Map.entry("submit", "document.submitted"),
            Map.entry("approve", "document.approved"), Map.entry("reject", "document.rejected"),
            Map.entry("confirm", "document.confirmed"), Map.entry("issue", "document.issued"),
            Map.entry("activate", "document.activated"), Map.entry("expire", "document.expired"),
            Map.entry("revoke", "document.revoked"), Map.entry("post", "document.posted"),
            Map.entry("pay", "document.paid"), Map.entry("close", "document.closed"),
            Map.entry("cancel", "document.cancelled"), Map.entry("reverse", "document.reversed"),
            Map.entry("reissue", "document.reissued"), Map.entry("terminate", "document.terminated"),
            Map.entry("archive", "document.archived"), Map.entry("amend", "document.amended"));

    /** One lifecycle transition row. */
    public record Transition(String fromState, String toState, String action, Map<String, Object> guardJson) {}

    /** Runtime facts the declarative guards are evaluated against. */
    public record GuardContext(String reason, boolean isPaid, boolean hasApprovedRequest, boolean hasSnapshot) {}

    /** {@code amend} validation verdict: merged data + notes override, or a blocking reason. */
    public record AmendVerdict(boolean ok, Map<String, Object> mergedData, String notes, boolean hasNotes,
            String reason) {}

    /** Eligibility verdict for cancel / reverse. */
    public record Eligibility(boolean allowed, String reason) {}

    /** The transition allowed for (fromState, action), or null. */
    public static Transition findTransition(List<Transition> transitions, String fromState, String action) {
        for (Transition t : transitions) {
            if (t.fromState().equals(fromState) && t.action().equals(action)) {
                return t;
            }
        }
        return null;
    }

    /** §5.3 amend validation — content-only, document-owned keys. */
    public static AmendVerdict validateAmendData(Map<String, Object> current, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return new AmendVerdict(false, null, null, false, "amend requires a non-empty data payload");
        }
        Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        String notes = null;
        boolean hasNotes = false;
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (!AMENDABLE_FIELDS.contains(e.getKey())) {
                return new AmendVerdict(false, null, null, false, "'" + e.getKey()
                        + "' is not amendable — document-owned content fields are: " + String.join(", ", AMENDABLE_FIELDS));
            }
            if (!(e.getValue() instanceof String s)) {
                return new AmendVerdict(false, null, null, false, "amend field '" + e.getKey() + "' must be a string");
            }
            if (e.getKey().equals("notes")) {
                notes = s;
                hasNotes = true;
            } else {
                merged.put(e.getKey(), s);
            }
        }
        return new AmendVerdict(true, merged, notes, hasNotes, null);
    }

    /** §4.4 snapshot policy — reverse always snapshots; otherwise per captureOn phases. */
    public static boolean shouldCaptureSnapshot(List<String> captureOn, String action, String toStatus) {
        if ("reverse".equals(action)) {
            return true;
        }
        if (captureOn == null || captureOn.isEmpty()) {
            return false;
        }
        if ("issue".equals(action) && captureOn.contains("issue")) {
            return true;
        }
        if ("post".equals(action) && captureOn.contains("post")) {
            return true;
        }
        if ("amend".equals(action) && captureOn.contains("amend")) {
            return true;
        }
        if (captureOn.contains("post") && "posted".equals(toStatus)) {
            return true;
        }
        return captureOn.contains("issue") && "issued".equals(toStatus);
    }

    /** Evaluate a transition's guardJson; returns the blocking reasons (empty = pass). */
    public static List<String> evaluateGuards(Map<String, Object> guard, GuardContext ctx) {
        List<String> blocked = new ArrayList<>();
        if (guard == null) {
            return blocked;
        }
        if (truthy(guard.get("requiresReason")) && (ctx.reason() == null || ctx.reason().trim().isEmpty())) {
            blocked.add("A reason is required for this action");
        }
        if (truthy(guard.get("requiresPaid")) && !ctx.isPaid()) {
            blocked.add("The document must be fully paid before this action");
        }
        if (truthy(guard.get("requiresApproval")) && !ctx.hasApprovedRequest()) {
            blocked.add("The document requires an approved approval request before this action");
        }
        if (truthy(guard.get("requiresSnapshot")) && !ctx.hasSnapshot()) {
            blocked.add("The document requires a snapshot before this action");
        }
        return blocked;
    }

    private static boolean truthy(Object v) {
        return v instanceof Boolean b ? b : v != null && !"".equals(v) && !Integer.valueOf(0).equals(v);
    }

    /** Same-state reversal (posted → posted via {@code reverse}). */
    public static boolean isReversal(Transition t) {
        return "reverse".equals(t.action()) && t.fromState().equals(t.toState());
    }

    /** A document may be cancelled only while it has no posted effects. */
    public static Eligibility cancelEligibility(boolean hasPostedEffects) {
        return hasPostedEffects
                ? new Eligibility(false, "Document has posted effects — use reverse instead of cancel")
                : new Eligibility(true, null);
    }

    /** Reverse requires posted effects and a registered counterpart type. */
    public static Eligibility reversalEligibility(boolean hasPostedEffects, String counterpartCode) {
        if (!hasPostedEffects) {
            return new Eligibility(false, "Document has no posted effects — use cancel instead of reverse");
        }
        if (counterpartCode == null) {
            return new Eligibility(false, "This document type is not reversable");
        }
        return new Eligibility(true, null);
    }

    /** {@code invoice} → {@code invoice:2026}. */
    public static String composeSequenceKey(String baseKey, int year) {
        return baseKey + ":" + year;
    }

    /** {@code INV-} → {@code INV-2026-}. */
    public static String composeSequencePrefix(String basePrefix, int year) {
        return (basePrefix == null ? "" : basePrefix) + year + "-";
    }

    /** {@code doc:<type>:<action>} → {@code doc:<category>:<action>} → {@code doc:<action>}; null when not granted. */
    @SuppressWarnings("unchecked")
    public static String resolveKey(List<String> userKeys, String code, String category, Object security,
            String action) {
        String base = "document";
        if (security instanceof Map<?, ?> m && m.get("basePermission") instanceof String s) {
            base = s;
        }
        if (code != null) {
            String exact = base + ":" + code + ":" + action;
            if (userKeys.contains(exact)) {
                return exact;
            }
            String cat = base + ":" + category + ":" + action;
            if (userKeys.contains(cat)) {
                return cat;
            }
        }
        String generic = base + ":" + action;
        return userKeys.contains(generic) ? generic : null;
    }

    /** {@link #resolveKey} != null. */
    public static boolean can(List<String> userKeys, String code, String category, Object security, String action) {
        return resolveKey(userKeys, code, category, security, action) != null;
    }

    /** Pure relation semantics check (§3.3); null when allowed, else the blocking reason. */
    public static String validateRelationCreation(String code, List<String> allowedSource, List<String> allowedTarget,
            String cardinality, String sourceCategory, String targetCategory, long currentSourceCount) {
        if (sourceCategory != null && !sourceCategory.isEmpty() && !allowedSource.isEmpty()
                && !allowedSource.contains(sourceCategory)) {
            return "Relation '" + code + "' does not allow source category '" + sourceCategory + "' (allowed: "
                    + String.join(", ", allowedSource) + ")";
        }
        if (targetCategory != null && !targetCategory.isEmpty() && !allowedTarget.isEmpty()
                && !allowedTarget.contains(targetCategory)) {
            return "Relation '" + code + "' does not allow target category '" + targetCategory + "' (allowed: "
                    + String.join(", ", allowedTarget) + ")";
        }
        if ("one".equals(cardinality) && currentSourceCount > 0) {
            return "Relation '" + code + "' is cardinality ONE — this document already has one";
        }
        return null;
    }
}
