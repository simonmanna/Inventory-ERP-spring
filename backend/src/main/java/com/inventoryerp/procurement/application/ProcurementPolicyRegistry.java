package com.inventoryerp.procurement.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The purchasing policy keys an organisation can configure (ADR-PROCUREMENT-001,
 * {@code docs/spec/procurement-approval-policies.md} §2). Each key has a type, a built-in default that equals the
 * behaviour before policies existed, and a validator. Only keys whose rule is enforced somewhere are registered.
 */
public final class ProcurementPolicyRegistry {

    /** Value shape of a key. */
    public enum Kind {
        ENUM, NUMBER, OPTIONAL_NUMBER, STRING_LIST, BOOLEAN
    }

    /**
     * One policy key: {@code group} is a UI section, {@code allowed} the enum values (ENUM only), {@code min}/{@code max}
     * bounds (numbers).
     */
    public record Definition(String key, String group, String label, String description, Kind kind,
            JsonNode defaultValue, List<String> allowed, Double min, Double max) {

        /** Plain map for the API. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("group", group);
            m.put("label", label);
            m.put("description", description);
            m.put("kind", kind.name().toLowerCase());
            m.put("default", defaultValue);
            m.put("allowed", allowed);
            m.put("min", min);
            m.put("max", max);
            return m;
        }
    }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Map<String, Definition> DEFS = new LinkedHashMap<>();

    public static final String SUPPLIER_APPROVED_REQUIRED = "supplier.approvedRequired";
    public static final String SUPPLIER_CERTIFICATES_REQUIRED = "supplier.certificatesRequired";
    public static final String ROUTE_REQUISITION_REQUIRED_ABOVE = "route.requisitionRequiredAbove";
    public static final String RECEIVING_OVER_RECEIPT_TOLERANCE = "receiving.overReceiptTolerancePct";
    public static final String BUDGET_CONTROL = "budget.control";
    public static final String MATCH_MODE = "match.mode";
    public static final String MATCH_PRICE_TOLERANCE = "match.priceTolerancePct";
    public static final String MATCH_QTY_TOLERANCE = "match.qtyTolerancePct";
    public static final String BILL_DUPLICATE_CHECK = "bill.duplicateCheck";
    public static final String CHANGE_REAPPROVAL_ABOVE = "change.reapprovalAbovePct";

    static {
        def(new Definition(SUPPLIER_APPROVED_REQUIRED, "supplier", "Approved supplier required",
                "What happens when an order is raised for a supplier whose profile is not 'approved': nothing (off),"
                        + " a warning on the order (warn) or refusal (block). Suspended / blacklisted suppliers are"
                        + " always refused.",
                Kind.ENUM, F.stringNode("off"), List.of("off", "warn", "block"), null, null));
        def(new Definition(SUPPLIER_CERTIFICATES_REQUIRED, "supplier", "Required supplier certificates",
                "Certificate types (e.g. tin, trade_licence, explosives_licence) a supplier must hold, valid on the"
                        + " order date, before an order can be raised. Set it per product category for controlled items.",
                Kind.STRING_LIST, F.arrayNode(), null, null, null));
        def(new Definition(ROUTE_REQUISITION_REQUIRED_ABOVE, "route", "Requisition required above",
                "Orders above this total (order currency) must come from an approved purchase request. Empty = direct"
                        + " orders are always allowed.",
                Kind.OPTIONAL_NUMBER, F.nullNode(), null, 0d, null));
        def(new Definition(RECEIVING_OVER_RECEIPT_TOLERANCE, "receiving", "Over-receipt tolerance (%)",
                "How much more than ordered may be received on a line, as a percentage of the ordered quantity"
                        + " (0 = never over-receive). Typical for bulk material weighed at the gate.",
                Kind.NUMBER, F.numberNode(0), null, 0d, 100d));
        def(new Definition(BUDGET_CONTROL, "budget", "Budget control",
                "Check approved budgets when an order is raised: off, warn (the order shows the overrun), block, or"
                        + " block_with_override (only a user with budget:override may raise it). Available budget ="
                        + " budget − posted actuals − open purchase commitments.",
                Kind.ENUM, F.stringNode("off"), List.of("off", "warn", "block", "block_with_override"), null, null));
        def(new Definition(MATCH_MODE, "matching", "Bill matching",
                "three_way compares the bill with what was received; two_way compares it with what was ordered (for"
                        + " services and direct deliveries). Empty = the organisation-wide match setting.",
                Kind.ENUM, F.stringNode("three_way"), List.of("three_way", "two_way"), null, null));
        def(new Definition(MATCH_PRICE_TOLERANCE, "matching", "Price tolerance (%)",
                "How far a billed unit price may differ from the order price before the bill line is held. Empty ="
                        + " the organisation-wide match setting (2 %).",
                Kind.OPTIONAL_NUMBER, F.nullNode(), null, 0d, 100d));
        def(new Definition(MATCH_QTY_TOLERANCE, "matching", "Quantity tolerance (%)",
                "How much more than received (or ordered, in two-way mode) may be billed before the line is held.",
                Kind.NUMBER, F.numberNode(0), null, 0d, 100d));
        def(new Definition(BILL_DUPLICATE_CHECK, "matching", "Duplicate bill check",
                "Before a supplier bill is posted, look for another bill from the same supplier with the same"
                        + " reference, or the same amount within 7 days: off, warn (posted, flagged) or block.",
                Kind.ENUM, F.stringNode("off"), List.of("off", "warn", "block"), null, null));
        def(new Definition(CHANGE_REAPPROVAL_ABOVE, "change", "Re-approval for order changes above (%)",
                "An order change that raises the order total by more than this percentage needs a second person"
                        + " (po_change:approve) before it applies. 0 = every increase; decreases never need approval.",
                Kind.NUMBER, F.numberNode(0), null, 0d, 1000d));
    }

    private ProcurementPolicyRegistry() {}

    private static void def(Definition d) {
        DEFS.put(d.key(), d);
    }

    /** All definitions in display order. */
    public static List<Definition> all() {
        return List.copyOf(DEFS.values());
    }

    /** Definition or a 400 for an unknown key. */
    public static Definition require(String key) {
        Definition d = DEFS.get(key);
        if (d == null) {
            throw ApiException.badRequest("Unknown procurement policy key: " + key);
        }
        return d;
    }

    /** Validate (and normalise) a value for {@code key}; 400 with a precise message when invalid. */
    public static JsonNode validate(String key, JsonNode value) {
        Definition d = require(key);
        JsonNode v = value == null ? F.nullNode() : value;
        String bad = "Invalid value for " + key + ": ";
        switch (d.kind()) {
            case ENUM -> {
                if (!v.isString() || !d.allowed().contains(v.asString())) {
                    throw ApiException.badRequest(bad + "must be one of " + String.join(", ", d.allowed()));
                }
            }
            case NUMBER, OPTIONAL_NUMBER -> {
                if (v.isNull() && d.kind() == Kind.OPTIONAL_NUMBER) {
                    return v;
                }
                if (!v.isNumber()) {
                    throw ApiException.badRequest(bad + "must be a number");
                }
                double n = v.asDouble();
                if (d.min() != null && n < d.min() || d.max() != null && n > d.max()) {
                    throw ApiException.badRequest(bad + "must be between " + fmt(d.min()) + " and "
                            + (d.max() == null ? "∞" : fmt(d.max())));
                }
            }
            case STRING_LIST -> {
                if (!v.isArray()) {
                    throw ApiException.badRequest(bad + "must be a list of text values");
                }
                for (JsonNode item : v) {
                    if (!item.isString() || item.asString().isBlank()) {
                        throw ApiException.badRequest(bad + "every entry must be non-empty text");
                    }
                }
            }
            case BOOLEAN -> {
                if (!v.isBoolean()) {
                    throw ApiException.badRequest(bad + "must be true or false");
                }
            }
        }
        return v;
    }

    private static String fmt(Double d) {
        return d == null ? "" : d % 1 == 0 ? String.valueOf(d.longValue()) : String.valueOf(d);
    }
}
