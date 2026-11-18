package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules of LP0 and their defaults (docs/spec/loss-prevention.md). An organization changes a rule through
 * {@code PUT /loss-prevention/rules/:code}; until it does, these apply as version 0.
 */
public final class LpRuleCatalog {

    private LpRuleCatalog() {}

    public static final String CASHIER = "cashier";
    public static final String BAR = "bar";

    /**
     * @param finalOnly the rule compares a whole business day, so it runs only once the day is over
     * @param params default parameters; a {@code null} value means the organization must set it before the rule runs
     */
    public record Definition(String code, String engine, String title, String severity, boolean enabled,
            boolean finalOnly, Map<String, Object> params) {}

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    private static void add(String code, String engine, String title, String severity, boolean enabled,
            boolean finalOnly, Map<String, Object> params) {
        DEFINITIONS.put(code, new Definition(code, engine, title, severity, enabled, finalOnly, params));
    }

    static {
        add("C01", CASHIER, "Item voided after it was sent to the kitchen", "warning", true, false, Obj.of());
        add("C02", CASHIER, "Order cancelled after items were sent to the kitchen", "warning", true, false, Obj.of());
        add("C03", CASHIER, "Refund at or above the threshold", "warning", true, false, Obj.of("amount", null));
        add("C04", CASHIER, "Refunds by one cashier well above the branch", "warning", true, true,
                Obj.of("multiplier", 3, "minCount", 3));
        add("C05", CASHIER, "Overrides for one cashier well above the branch", "warning", true, true,
                Obj.of("multiplier", 3, "minCount", 5));
        add("C06", CASHIER, "One manager approves most of a cashier's overrides", "info", true, true,
                Obj.of("sharePercent", 60, "minCount", 5, "days", 7));
        add("C07", CASHIER, "Discount rate of one cashier well above the branch", "warning", true, true,
                Obj.of("multiplier", 2, "minSales", 10, "minRatePercent", 5));
        add("C08", CASHIER, "Cash session difference above tolerance", "warning", true, false,
                Obj.of("amount", null));
        add("C09", CASHIER, "Repeated short cash sessions for one cashier", "critical", true, false,
                Obj.of("sessions", 10, "minShort", 4));
        add("C10", CASHIER, "Kitchen ticket recalled or cancelled after it was ready", "info", true, false, Obj.of());
        add("B01", BAR, "Unexplained bottle variance above tolerance", "warning", true, false,
                Obj.of("toleranceMl", 30));
        add("B02", BAR, "Same cause given for one product in consecutive counts", "info", true, false,
                Obj.of("counts", 4, "minRepeats", 3));
        // Off until the tie-out has been checked against an organization's real counts: see the spec.
        add("B03", BAR, "Stock movements do not tie to system stock", "info", false, false, Obj.of("roundingMl", 1));
    }

    public static List<Definition> definitions() {
        return List.copyOf(DEFINITIONS.values());
    }

    public static Definition definition(String code) {
        return DEFINITIONS.get(code);
    }
}
