package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules of LP0, LP2, LP3, LP4 and LP5 and their defaults (docs/spec/loss-prevention.md). An organization changes a rule through
 * {@code PUT /loss-prevention/rules/:code}; until it does, these apply as version 0.
 */
public final class LpRuleCatalog {

    private LpRuleCatalog() {}

    public static final String CASHIER = "cashier";
    public static final String BAR = "bar";
    /** LP2: what a camera saw at a till against what the POS recorded there. Runs only where zones are drawn. */
    public static final String CASHIER_VISION = "cashier_vision";
    /** LP3: pour meters and bar cameras against what sales drew. Runs only where a meter or a bar zone exists. */
    public static final String BAR_POUR = "bar_pour";
    /** LP4: the kitchen's stages against the sales of their orders. Needs no camera. */
    public static final String KITCHEN_FLOW = "kitchen_flow";
    /** LP4: plates over the kitchen pass and items over the cafe counter, against tickets and sales. */
    public static final String SERVICE_VISION = "service_vision";
    /** LP5: items over a checkout's scanner against its sales, and store-room carry-outs against stock movements. */
    public static final String RETAIL_VISION = "retail_vision";

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
        add("C11", CASHIER, "No-sale drawer opens by one cashier well above the branch", "warning", true, true,
                Obj.of("multiplier", 3, "minCount", 5));
        add("B01", BAR, "Unexplained bottle variance above tolerance", "warning", true, false,
                Obj.of("toleranceMl", 30));
        add("B02", BAR, "Same cause given for one product in consecutive counts", "info", true, false,
                Obj.of("counts", 4, "minRepeats", 3));
        // Off until the tie-out has been checked against an organization's real counts: see the spec.
        add("B03", BAR, "Stock movements do not tie to system stock", "info", false, false, Obj.of("roundingMl", 1));
        // LP2. Windows are in seconds (at most 900); a confidence is from 0 to 1. Accuracy is not yet measured on
        // real footage: see docs/phases/LP2-cashier-vision.md.
        add("V-C1", CASHIER_VISION, "Cash drawer opened with no sale, refund or cash movement", "warning", true, false,
                Obj.of("windowBeforeSeconds", 60, "windowAfterSeconds", 120, "minConfidence", new BigDecimal("0.6")));
        add("V-C2", CASHIER_VISION, "Refund or void with no customer at the till", "warning", true, false,
                Obj.of("windowBeforeSeconds", 120, "windowAfterSeconds", 60, "minPresenceSeconds", 5,
                        "minConfidence", new BigDecimal("0.5")));
        add("V-C3", CASHIER_VISION, "Customer served with no sale recorded", "info", true, false,
                Obj.of("minDwellSeconds", 90, "windowBeforeSeconds", 0, "windowAfterSeconds", 300, "minConfidence",
                        new BigDecimal("0.6")));
        // LP3. Meter readings and camera observations are trusted as sent; nothing is measured on a real bar yet:
        // see docs/phases/LP3-bar-intelligence.md.
        add("B04", BAR_POUR, "More volume poured of a product than its sales drew", "warning", true, true,
                Obj.of("tolerancePercent", 5, "minExcessMl", 100));
        add("B05", BAR_POUR, "Pours well above the standard measure at one device", "info", true, true,
                Obj.of("tolerancePercent", 15, "minCount", 5, "minSharePercent", 20));
        add("V-B1", BAR_POUR, "Bottle poured at the bar counter with no drink sale near it", "info", true, false,
                Obj.of("windowBeforeSeconds", 120, "windowAfterSeconds", 600, "minConfidence", new BigDecimal("0.6"),
                        "minTiltSeconds", 1));
        add("V-B2", BAR_POUR, "Pour seen by the camera with no meter reading", "warning", true, false,
                Obj.of("windowSeconds", 30, "minConfidence", new BigDecimal("0.6"), "minTiltSeconds", 1));
        // LP4: docs/phases/LP4-restaurant-cafe.md.
        add("K01", KITCHEN_FLOW, "Food served from the kitchen with no sale recorded", "warning", true, true,
                Obj.of());
        add("K02", KITCHEN_FLOW, "Remakes at one station above the threshold", "info", true, true,
                Obj.of("maxRatePercent", 10, "minCount", 3));
        add("V-K1", SERVICE_VISION, "More plates left the kitchen pass than the tickets account for", "warning", true,
                false, Obj.of("bucketMinutes", 60, "slackMinutes", 10, "tolerancePercent", 25, "minExcess", 3,
                        "minConfidence", new BigDecimal("0.5")));
        add("V-F1", SERVICE_VISION, "More items handed over at the cafe counter than were sold", "warning", true,
                false, Obj.of("bucketMinutes", 60, "slackMinutes", 5, "tolerancePercent", 20, "minExcess", 3,
                        "minConfidence", new BigDecimal("0.5")));
        // LP5: docs/phases/LP5-retail-checkout.md. V-R1 and V-R2 ship off until a pilot has measured the count
        // error per transaction batch; an organization turns them on.
        add("V-R1", RETAIL_VISION, "More items passed the scanner than the sale records", "warning", false, false,
                Obj.of("maxBatchMinutes", 20, "tolerancePercent", 10, "minExcess", 2, "minConfidence",
                        new BigDecimal("0.5")));
        add("V-R2", RETAIL_VISION, "Items carried past the scanner that the sale does not explain", "warning", false,
                false, Obj.of("maxBatchMinutes", 20, "minCount", 1, "minConfidence", new BigDecimal("0.5")));
        add("V-S1", RETAIL_VISION, "Goods carried out of a store room with no stock movement recorded", "warning",
                true, false, Obj.of("bucketMinutes", 60, "slackMinutes", 30, "minCount", 2, "minConfidence",
                        new BigDecimal("0.5")));
    }

    public static List<Definition> definitions() {
        return List.copyOf(DEFINITIONS.values());
    }

    public static Definition definition(String code) {
        return DEFINITIONS.get(code);
    }
}
