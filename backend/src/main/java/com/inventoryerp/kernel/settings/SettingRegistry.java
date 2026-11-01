package com.inventoryerp.kernel.settings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Setting registry: the single source of truth for configurable settings (port of
 * {@code setting-registry.ts}). Scope precedence (most specific first): product → category →
 * warehouse → organization → registry default.
 */
public final class SettingRegistry {

    public static final List<String> SCOPE_PRECEDENCE = List.of("product", "category", "warehouse", "organization");
    public static final List<String> SCOPE_TYPES = List.of("organization", "warehouse", "category", "product");

    private static final List<String> ALL_LEVELS = List.of("organization", "warehouse", "category", "product");
    private static final List<String> ORG_ONLY = List.of("organization");

    /** One registered setting. */
    public record Definition(String key, String group, String type, String label, String description, Object defaultValue,
            List<String> enumValues, Map<String, String> unavailableEnumValues, boolean cascades, List<String> scopeLevels,
            boolean deprecated) {}

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    private SettingRegistry() {}

    private static void def(String key, String group, String type, String label, String description, Object dflt,
            List<String> enumValues, boolean cascades, List<String> levels) {
        DEFINITIONS.put(key, new Definition(key, group, type, label, description, dflt, enumValues, Map.of(), cascades,
                levels, false));
    }

    private static void deprecated(String key, String label, String description) {
        DEFINITIONS.put(key, new Definition(key, "accounting", "string", label, description, "", null, Map.of(), false,
                ORG_ONLY, true));
    }

    static {
        // ---- Inventory ----
        def("inventory.allowNegativeStock", "inventory", "bool", "Allow Negative Stock",
                "When off, stock-out enforcement follows the item stock policy. Default keeps sales unblocked.",
                true, null, true, ALL_LEVELS);
        def("inventory.stockInApprovalNeeded", "inventory", "bool", "Stock In Approval Needed",
                "When on, a direct stock-in needs a named approver (with PIN unless self-approved). When off, it posts with attribution to the responsible staff only.",
                true, null, false, ORG_ONLY);
        def("inventory.stockOutApprovalNeeded", "inventory", "bool", "Stock Out Approval Needed",
                "When on, a direct stock-out needs a named approver (with PIN unless self-approved). When off, it posts with attribution to the responsible staff only.",
                true, null, false, ORG_ONLY);
        def("inventory.atpMode", "inventory", "enum", "Available-to-Promise Mode",
                "post_commit = sale proceeds, reservation is best-effort (default). "
                        + "pre_invoice = check ATP before invoice, warn if insufficient. "
                        + "strict = check ATP before invoice and refuse the sale if insufficient. "
                        + "This is an advisory pre-check, not a reservation: two terminals selling the "
                        + "last unit at the same instant can both pass. For a hard stock ceiling also set "
                        + "Allow Negative Stock off with the item stock policy \"block\".",
                "post_commit", List.of("post_commit", "pre_invoice", "strict"), true, ALL_LEVELS);
        def("inventory.serialPolicy", "inventory", "enum", "Serial Number Policy",
                "capture_optional = serials are recorded when supplied; quantity-only movements are allowed (default). "
                        + "required = every receipt, adjustment gain and issue of a serial-tracked item must name one serial per unit.",
                "capture_optional", List.of("capture_optional", "required"), true, ALL_LEVELS);
        def("inventory.expiredStockPolicy", "inventory", "enum", "Expired Stock Policy",
                "allow = expired batches remain pickable (default). "
                        + "skip_expired = FEFO/FIFO picking never consumes a batch past its expiry date; write it off via Waste instead.",
                "allow", List.of("allow", "skip_expired"), true, ALL_LEVELS);
        def("inventory.defaultPickingStrategy", "inventory", "enum", "Removal Strategy",
                "Overrides the product removal strategy for the chosen scope at issue time. Product column is the fallback.",
                "FEFO", List.of("FEFO", "FIFO", "MANUAL", "SERIAL"), true, ALL_LEVELS);
        def("inventory.defaultCostingMethod", "inventory", "enum", "Default Costing Method (new products)", null,
                "AVCO", List.of("AVCO", "FIFO", "STANDARD", "SPECIFIC"), false, ORG_ONLY);
        // ---- Costing & valuation (Cost Management CM-2, ADR-S032) ----
        def("costing.landedCost.enabled", "costing", "bool", "Landed Costs",
                "Add additional costs (transport, customs, insurance, handling…) to the value of the goods they brought"
                        + " in. When off, no new landed cost voucher can be created.",
                true, null, false, ORG_ONLY);
        def("costing.landedCost.defaultAllocationMethod", "costing", "enum", "Landed Cost Default Allocation",
                "Basis used by a landed cost charge when neither the charge nor its cost component names one.",
                "value", List.of("value", "quantity", "equal", "weight", "volume", "manual"), false, ORG_ONLY);
        def("costing.dimensionEnforcement", "costing", "enum", "Costing Branch Dimension",
                "off = not checked; warn = costing postings without a branch are listed in the dimension compliance"
                        + " report (default); enforce = a costing posting without a branch is refused.",
                "warn", List.of("off", "warn", "enforce"), false, ORG_ONLY);
        def("inventory.defaultBatchTracking", "inventory", "bool", "Default Batch Tracking (new products)", null,
                false, null, false, ORG_ONLY);
        def("inventory.defaultExpiryTracking", "inventory", "bool", "Default Expiry Tracking (new products)", null,
                false, null, false, ORG_ONLY);
        def("inventory.defaultSerialTracking", "inventory", "bool", "Default Serial Tracking (new products)", null,
                false, null, false, ORG_ONLY);
        def("inventory.reservationMode", "inventory", "enum", "Stock Reservation",
                "Reserve stock (available-to-promise) when a sales order or invoice is raised. Released on cancel/void.",
                "none", List.of("none", "order", "invoice"), false, ORG_ONLY);
        DEFINITIONS.put("inventory.stockPostingTiming", new Definition("inventory.stockPostingTiming", "inventory", "enum",
                "Stock Deduction Timing",
                "When stock is deducted for a sale: at invoice (default), when fulfillment completes (e.g. all kitchen tickets served / goods dispatched), at payment, or manually. \"At fulfillment start\" is not yet available.",
                "at_invoice",
                List.of("at_confirmation", "at_fulfillment_start", "at_fulfillment_complete", "at_invoice", "at_payment", "manual"),
                Map.of("at_fulfillment_start",
                        "deducting at fulfillment start has no reversal path for an order cancelled before it is invoiced; enable once cancel-before-invoice stock reversal exists"),
                false, ORG_ONLY, false));
        def("inventory.batchAutoNumber", "inventory", "bool", "Auto-generate Batch Numbers",
                "When a batch-tracked receipt omits a batch number, generate one from the format below.",
                false, null, false, ORG_ONLY);
        def("inventory.batchNumberFormat", "inventory", "string", "Batch Number Format",
                "Tokens: YYYY MM DD and #### (sequence). Example: YYYYMMDD-####", "YYYYMMDD-####", null, false, ORG_ONLY);

        // ---- Accounting ----
        def("accounting.fiscalYearStartMonth", "accounting", "number", "Fiscal Year Start Month",
                "1 = January … 12 = December. Used when generating fiscal periods.", 1, null, false, ORG_ONLY);
        deprecated("accounting.incomeAccountId", "Default Income Account",
                "DEPRECATED — the posting engine never read this key. Account determination uses the AccountMapping key `sales_revenue`.");
        deprecated("accounting.expenseAccountId", "Default Expense Account",
                "DEPRECATED — the posting engine never read this key. Account determination uses the AccountMapping key `default_expense`.");
        def("accounting.defaultSalesTaxId", "accounting", "string", "Default Sales Tax",
                "Default tax applied to sales transactions.", "", null, false, ORG_ONLY);
        def("accounting.exchangeDifferenceJournalId", "accounting", "string", "Exchange Difference Journal",
                "Journal used for recording exchange rate differences.", "", null, false, ORG_ONLY);
        def("accounting.defaultSalesJournalId", "accounting", "string", "Default Sales Journal",
                "Journal used by default for customer invoice / sales journal entries when the invoice does not pick a specific journal.",
                "", null, false, ORG_ONLY);
        deprecated("accounting.exchangeGainAccountId", "Exchange Gain Account",
                "DEPRECATED — the posting engine never read this key. FX posting uses the AccountMapping key `fx_gain`.");
        deprecated("accounting.exchangeLossAccountId", "Exchange Loss Account",
                "DEPRECATED — the posting engine never read this key. FX posting uses the AccountMapping key `fx_loss`.");
        deprecated("accounting.productIncomeAccountId", "Product Income Account",
                "DEPRECATED — the posting engine never read this key. Account determination uses the product category incomeAccountId, then the AccountMapping key `sales_revenue`.");
        deprecated("accounting.productExpenseAccountId", "Product Expense Account",
                "DEPRECATED — the posting engine never read this key. Account determination uses the product category expenseAccountId, then the AccountMapping key `default_expense`.");
        def("accounting.allCurrencyCodes", "accounting", "json", "Enabled Currencies",
                "List of currency codes enabled for this organization.", List.of(), null, false, ORG_ONLY);
        def("pos.stockLocationId", "inventory", "string", "POS Stock Location",
                "Location the POS sells stock from. Blank = first active warehouse.", "", null, false, ORG_ONLY);
        def("pos.storeCreditIssueLimit", "accounting", "number", "Store Credit Issue Limit",
                "Maximum amount for a single manual store-credit issue. 0 disables manual issuance entirely.", 0, null,
                false, ORG_ONLY);

        // ---- Rental ----
        def("rental.autoHoldMinutes", "rental", "number", "Reservation Hold (minutes)",
                "How long a reservation hold stays valid before the cron worker expires it.", 60, null, false, ORG_ONLY);
        def("rental.cleaningTurnaroundDays", "rental", "number", "Cleaning Turnaround (days)",
                "Default buffer between a return and the unit being available again.", 1, null, false, ORG_ONLY);
        def("rental.lateFeePerDay", "rental", "number", "Late Fee per Day (IDR)",
                "Default daily late fee charged at settlement. Per-product rentalLateFeePerPeriod overrides.", 0, null,
                false, ORG_ONLY);
        def("rental.dispositionPolicy", "rental", "json", "Disposition Policy",
                "JSON array of DispositionRule: inspection outcome → next unit state.", List.of(), null, false, ORG_ONLY);
        Map<String, Object> deposit = new LinkedHashMap<>();
        deposit.put("mode", "percent");
        deposit.put("percent", 10);
        def("rental.depositPolicy", "rental", "json", "Deposit Policy",
                "JSON: { mode: fixed|percent|replacement, percent, fixedAmount }. Fallback: 10% of replacement cost.",
                deposit, null, false, ORG_ONLY);

        // ---- Fleet & logistics (ADR-S009) ----
        def("fleet.complianceReminderDays", "fleet", "number", "Compliance Reminder Lead (days)",
                "Default number of days before a vehicle's registration, insurance, licence, inspection or permit "
                        + "expires that it is reported as expiring soon. Each document can override it.", 30, null,
                false, ORG_ONLY);
        def("fleet.maintenanceLabourAbsorption", "fleet", "bool", "Post Workshop Labour to Maintenance Cost",
                "When on, labour booked on fleet work orders is posted Dr fleet maintenance (vehicle cost centre) / "
                        + "Cr workshop labour absorbed. Off: labour is a memo cost (payroll already expenses wages).",
                false, null, false, ORG_ONLY);
        // Fleet 1.9 — costing, freight bills, reminders.
        def("fleet.tripCostBasis", "fleet", "enum", "Own-fleet Trip Cost Basis",
                "How a unit's cost of the period is allocated to its trips in delivery profitability and transport "
                        + "cost reports: km = cost per km x trip distance (default), tonne = cost per tonne x tonnes "
                        + "delivered, trip = cost per trip. Falls back km, tonne, trip when a figure is missing.",
                "km", List.of("km", "tonne", "trip"), false, ORG_ONLY);
        def("fleet.freightMatchTolerancePct", "fleet", "number", "Freight Bill Match Tolerance (%)",
                "A transporter invoice within this % of the trips' freight (or the absolute tolerance, whichever is "
                        + "larger) is matched at once; above it the freight bill is disputed until a manager accepts.",
                1, null, false, ORG_ONLY);
        def("fleet.freightMatchToleranceAmount", "fleet", "number", "Freight Bill Match Tolerance (amount)",
                "Absolute amount a transporter invoice may differ from the trips' freight and still match.", 0, null,
                false, ORG_ONLY);
        def("fleet.tareReminderDays", "fleet", "number", "Stored Tare Reminder Lead (days)",
                "Days before a stored weighbridge tare expires that the daily reminder job warns about it.", 3, null,
                false, ORG_ONLY);

        // ---- Purchasing ----
        def("purchasing.editProductPriceFromPurchaseScreen", "purchasing", "bool",
                "Enable editing product price from purchase screen",
                "Allow the unit cost / price to be edited directly on the purchase (receive) screen.", false, null, false, ORG_ONLY);
        def("purchasing.enablePurchaseStatus", "purchasing", "bool", "Enable Purchase Status",
                "Track lifecycle status (draft / ordered / received / billed) on purchases.", false, null, false, ORG_ONLY);
        def("purchasing.enableLotBatchOnPurchase", "purchasing", "bool", "Enable lot / batch number on purchases",
                "Require or allow a lot / batch number when receiving purchased stock.", false, null, false, ORG_ONLY);
        def("purchasing.enablePurchaseOrder", "purchasing", "bool", "Enable Purchase Order",
                "Allow creating purchase orders that are received into stock.", false, null, false, ORG_ONLY);
        def("purchasing.enablePurchaseRequisition", "purchasing", "bool", "Enable Purchase Requisition",
                "Allow raising internal purchase requisitions before a purchase order.", false, null, false, ORG_ONLY);
        def("purchasing.enableReceiptReminder", "purchasing", "bool", "Receipt Reminder",
                "Enable reminders for expected receipt dates on purchases.", false, null, false, ORG_ONLY);
        def("purchasing.autoRemindReceiptToVendors", "purchasing", "bool",
                "Automatically remind the receipt date to your vendors",
                "Send automatic receipt-date reminders to vendors when enabled.", false, null, false, ORG_ONLY);
    }

    public static Definition get(String key) {
        return DEFINITIONS.get(key);
    }

    public static List<Definition> forGroup(String group) {
        List<Definition> out = new ArrayList<>();
        for (Definition d : DEFINITIONS.values()) {
            if (d.group().equals(group)) {
                out.add(d);
            }
        }
        return out;
    }

    /** Validate + normalise a raw value; throws {@link IllegalArgumentException} with the Nest message. */
    public static Object coerce(Definition def, Object raw) {
        switch (def.type()) {
            case "bool" -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                if ("true".equals(raw)) {
                    return true;
                }
                if ("false".equals(raw)) {
                    return false;
                }
                throw new IllegalArgumentException("Setting '" + def.key() + "' expects a boolean.");
            }
            case "number" -> {
                Object n = toNumber(raw);
                if (n == null) {
                    throw new IllegalArgumentException("Setting '" + def.key() + "' expects a number.");
                }
                return n;
            }
            case "enum" -> {
                String s = jsString(raw);
                if (def.enumValues() == null || !def.enumValues().contains(s)) {
                    throw new IllegalArgumentException("Setting '" + def.key() + "' must be one of: "
                            + String.join(", ", def.enumValues() == null ? List.of() : def.enumValues()) + ".");
                }
                String unavailable = def.unavailableEnumValues().get(s);
                if (unavailable != null) {
                    throw new IllegalArgumentException("Setting '" + def.key() + "' value '" + s
                            + "' is not available yet: " + unavailable);
                }
                return s;
            }
            case "string" -> {
                return jsString(raw);
            }
            default -> {
                return raw;
            }
        }
    }

    /** Assert a scope level is permitted for a key. */
    public static void assertScopeAllowed(Definition def, String scopeType) {
        if ("organization".equals(scopeType)) {
            return;
        }
        if (!def.cascades() || !def.scopeLevels().contains(scopeType)) {
            throw new IllegalArgumentException("Setting '" + def.key() + "' cannot be set at " + scopeType + " level.");
        }
    }

    /** JS {@code Number(raw)}, finite only; integral results stay integers. */
    private static Object toNumber(Object raw) {
        double d;
        if (raw instanceof Number n) {
            d = n.doubleValue();
        } else if (raw instanceof Boolean b) {
            d = b ? 1 : 0;
        } else if (raw == null) {
            d = 0;
        } else if (raw instanceof String s) {
            d = com.inventoryerp.kernel.common.JsNumbers.number(s);
        } else {
            return null;
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return null;
        }
        if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) {
            return (long) d;
        }
        return BigDecimal.valueOf(d).doubleValue();
    }

    /** JS {@code String(raw)}. */
    static String jsString(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            return String.valueOf(d.longValue());
        }
        if (raw instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object o : l) {
                parts.add(o == null ? "" : jsString(o));
            }
            return String.join(",", parts);
        }
        if (raw instanceof Map<?, ?>) {
            return "[object Object]";
        }
        return String.valueOf(raw);
    }
}
